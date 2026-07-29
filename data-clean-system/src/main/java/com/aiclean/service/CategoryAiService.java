package com.aiclean.service;

import cn.hutool.core.util.StrUtil;
import com.aiclean.ai.AiClientService;
import com.aiclean.dto.SimilarMaterialDTO;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.mapper.CleanedDataMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 标准分类代码 AI 问答服务。
 *
 * 思路（RAG-lite，避免 text-to-SQL 风险）：
 *   1) 从用户问题中检索 main_data_category（复用 CategoryStandardLibrary 内存索引）；
 *   2) 把命中的标准分类记录作为上下文注入系统提示词；
 *   3) 交由通用大模型基于“仅限该上下文”作答，并回传命中的来源记录供前端展示。
 *
 * 检索策略覆盖：编码精确/前缀、层级（一/二/三级）、名称/分词关键词、子树与祖先链。
 */
@Service
@Slf4j
public class CategoryAiService {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\d{2,6}");

    private final CategoryStandardLibrary library;
    private final AiClientService aiClientService;
    private final CleanedDataMapper cleanedDataMapper;

    @Value("${app.ai.category-top-k:15}")
    private int topK;

    @Value("${app.ai.category-max-context:40}")
    private int maxContext;

    @Value("${app.ai.category-system-prompt:}")
    private String categorySystemPrompt;

    // ===== 相似物料推荐配置 =====
    /** 从 cleaned_data 召回的候选上限（再在内存中按相似度排序裁剪） */
    @Value("${app.ai.similar-limit:200}")
    private int similarLimit;
    /** 最终返回给前端的相似物料条数上限 */
    @Value("${app.ai.similar-top-n:10}")
    private int similarTopN;
    /** 用于检索的关键词（分词）数量上限，避免单字/常见词拉回过多噪声 */
    @Value("${app.ai.similar-max-tokens:6}")
    private int similarMaxTokens;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一名“标准分类代码查询助手”，专门基于下方提供的【标准分类库（main_data_category）】数据回答用户关于标准分类的问题。\n" +
            "规则：\n" +
            "1. 只能依据【标准分类库】中给出的记录作答，不得编造库中不存在的分类编码或名称。\n" +
            "2. 若用户给出一段物料名称/描述并询问应归入哪个标准分类，请在库中检索最匹配的分类，给出分类编码与名称，并说明理由。\n" +
            "3. 若库中确实没有相关信息，明确告知“标准分类库中未找到相关记录”，不要臆测。\n" +
            "4. 回答使用简洁中文；涉及分类时附上分类编码（如 100101）与完整路径（full_path）。\n" +
            "5. 可以列举、对比、解释分类的层级关系、计量单位、说明等字段。";

    public CategoryAiService(CategoryStandardLibrary library, AiClientService aiClientService, CleanedDataMapper cleanedDataMapper) {
        this.library = library;
        this.aiClientService = aiClientService;
        this.cleanedDataMapper = cleanedDataMapper;
    }

    /**
     * 标准分类问答。
     *
     * @param messages 完整对话历史（含用户当前问题，最后一条应为 user）
     * @param question 用户当前问题（用于检索上下文）
     * @return 含 AI 回复与命中的标准分类来源
     */
    public CategoryChatResult chat(List<Map<String, String>> messages, String question) {
        if (!aiClientService.isEnabled()) {
            throw new RuntimeException("AI 对话功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        List<CategoryEntity> context = retrieve(question);
        String systemPrompt = buildSystemPrompt(context);
        String reply = aiClientService.chatWithHistory(systemPrompt, messages);
        CategoryChatResult result = new CategoryChatResult();
        result.setReply(reply);
        result.setSources(context);
        return result;
    }

    // ===================== 相似物料推荐 =====================

    private static final String SIMILAR_SYSTEM_PROMPT =
            "你是一名“相似物料推荐助手”，基于下方【检索到的相似物料】列表，回答用户关于“哪些物料与给定物料相似”的问题。\n" +
            "规则：\n" +
            "1. 只能依据【检索到的相似物料】列表作答，不得编造列表中不存在的物料。\n" +
            "2. 优先说明检索依据：这些物料与用户给定的物料在名称/规格/牌号/描述上存在哪些共同关键词，因此被判为相似。\n" +
            "3. 可按相似度由高到低简要列举若干条，并标注其分类与编码，帮助用户快速定位。\n" +
            "4. 若列表为空，礼貌说明“未检索到相似物料”，并建议用户更换或补充物料描述。\n" +
            "5. 回答使用简洁中文。";

    /**
     * 相似物料推荐。
     * 思路：先从用户问题中抽取“目标物料描述”，对其分词后在 cleaned_data 中做多关键词 OR 模糊召回，
     * 再按“查询词命中覆盖率”在内存中排序裁剪，最后交由大模型基于召回结果做自然语言综述。
     *
     * @param messages 完整对话历史（最后一条应为 user）
     * @param question 用户当前问题（用于抽取目标物料与检索）
     * @return 含 AI 综述回复与召回的相似物料来源
     */
    public SimilarMaterialResult recommendSimilarMaterials(List<Map<String, String>> messages, String question) {
        if (!aiClientService.isEnabled()) {
            throw new RuntimeException("AI 对话功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        String query = extractSimilarQuery(question);
        List<SimilarMaterialDTO> materials;
        if (StrUtil.isBlank(query)) {
            materials = new ArrayList<>();
        } else {
            List<String> tokens = tokenize(query);
            if (tokens.size() > similarMaxTokens) tokens = tokens.subList(0, similarMaxTokens);
            List<CleanedDataEntity> candidates = cleanedDataMapper.searchSimilarMaterials(tokens, similarLimit);
            materials = rankMaterials(tokens, candidates);
            log.info("相似物料推荐：问题=[{}]，抽取查询=[{}]，召回候选 {} 条，命中相似 {} 条",
                    question, query, candidates.size(), materials.size());
        }

        String reply;
        try {
            String systemPrompt = SIMILAR_SYSTEM_PROMPT + "\n\n【检索到的相似物料（已按相似度排序，共 "
                    + materials.size() + " 条）】\n" + formatMaterials(materials);
            reply = aiClientService.chatWithHistory(systemPrompt, messages);
        } catch (Exception e) {
            log.warn("相似物料推荐 AI 综述失败，回退为本地生成文本", e);
            reply = buildFallbackReply(materials, query);
        }

        SimilarMaterialResult result = new SimilarMaterialResult();
        result.setReply(reply);
        result.setMaterials(materials);
        return result;
    }

    /** 在召回候选中按“查询词命中覆盖率”排序，裁剪为 Top-N 相似物料 */
    private List<SimilarMaterialDTO> rankMaterials(List<String> queryTokens, List<CleanedDataEntity> candidates) {
        if (queryTokens.isEmpty() || candidates == null || candidates.isEmpty()) return new ArrayList<>();
        Set<String> qTokens = new LinkedHashSet<>(queryTokens);
        List<SimilarMaterialDTO> out = new ArrayList<>();
        for (CleanedDataEntity m : candidates) {
            String hay = String.join(" ",
                    nvl(m.getMaterialName()), nvl(m.getSpecification()),
                    nvl(m.getFullDescription()), nvl(m.getGrade()));
            Set<String> mTokens = new HashSet<>(tokenize(hay));
            Set<String> hit = new LinkedHashSet<>();
            for (String t : qTokens) {
                if (mTokens.contains(t)) hit.add(t);
            }
            if (hit.isEmpty()) continue;
            SimilarMaterialDTO dto = toDto(m);
            dto.setSimilarityScore(Math.round((double) hit.size() / qTokens.size() * 100.0) / 100.0);
            dto.setMatchedTokens(String.join("、", hit));
            dto.setReason("与查询词「" + String.join("、", hit) + "」匹配");
            out.add(dto);
        }
        out.sort((a, b) -> Double.compare(b.getSimilarityScore(), a.getSimilarityScore()));
        if (out.size() > similarTopN) out = out.subList(0, similarTopN);
        return out;
    }

    private SimilarMaterialDTO toDto(CleanedDataEntity m) {
        SimilarMaterialDTO d = new SimilarMaterialDTO();
        d.setId(m.getId());
        d.setMaterialCode(m.getMaterialCode());
        d.setMaterialName(m.getMaterialName());
        d.setSpecification(m.getSpecification());
        d.setGrade(m.getGrade());
        d.setUnit(m.getUnit());
        d.setCategoryCode(m.getCategoryCode());
        d.setCategoryName(m.getCategoryName());
        d.setCategoryFullPath(m.getCategoryFullPath());
        return d;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 将相似物料列表格式化为可供大模型阅读的文本 */
    private String formatMaterials(List<SimilarMaterialDTO> list) {
        if (list == null || list.isEmpty()) return "（未检索到相似物料）\n";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SimilarMaterialDTO m : list) {
            sb.append(i++).append(". ")
                    .append("[物料代码 ").append(m.getMaterialCode() == null ? "-" : m.getMaterialCode()).append("] ")
                    .append(m.getMaterialName() == null ? "-" : m.getMaterialName());
            if (StrUtil.isNotBlank(m.getSpecification())) sb.append(" 规格=").append(m.getSpecification());
            if (StrUtil.isNotBlank(m.getGrade())) sb.append(" 牌号=").append(m.getGrade());
            sb.append(" | 分类=").append(m.getCategoryName() == null ? "-" : m.getCategoryName())
                    .append("(").append(m.getCategoryCode() == null ? "-" : m.getCategoryCode()).append(")");
            sb.append(" | 相似度=").append(m.getSimilarityScore() == null ? "-" : (Math.round(m.getSimilarityScore() * 100) + "%"));
            sb.append(" | 命中词=").append(m.getMatchedTokens() == null ? "-" : m.getMatchedTokens());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** AI 综述失败时的本地回退文本 */
    private String buildFallbackReply(List<SimilarMaterialDTO> list, String query) {
        if (list == null || list.isEmpty()) {
            return "未检索到" + (StrUtil.isBlank(query) ? "相似物料" : "与「" + query + "」相似的物料")
                    + "。建议更换或更具体地描述物料名称/规格/牌号。";
        }
        StringBuilder sb = new StringBuilder("为你找到 ").append(list.size()).append(" 条相似物料（按相似度排序）：\n");
        for (SimilarMaterialDTO m : list) {
            sb.append("- ").append(m.getMaterialName())
                    .append("（").append(m.getCategoryName()).append("，分类编码 ").append(m.getCategoryCode())
                    .append("，相似度 ").append(Math.round(m.getSimilarityScore() * 100)).append("%）\n");
        }
        return sb.toString();
    }

    /**
     * 从用户问题中抽取“目标物料描述”。
     * 支持：① “相似物料：XXX”；② “和XXX相似的物料”；③ 去除引导词后的主体。
     */
    private String extractSimilarQuery(String q) {
        if (StrUtil.isBlank(q)) return "";
        String t = q.trim();
        // 模式①：推荐/找/查询 + 相似/类似/相近 + 物料/材料/产品 + 冒号 + 内容
        Matcher m1 = Pattern.compile("(?:推荐|找|查询|查|给?我?)?\\s*(?:相似|类似|相近|差不多)\\s*(?:物料|材料|产品)\\s*[:：]\\s*(.+)").matcher(t);
        if (m1.find()) return m1.group(1).trim();
        // 模式②：和/跟/与/同 + 主体 + 相似/类似/相近 + （物料）
        Matcher m2 = Pattern.compile("(?:和|跟|与|同)\\s*(.+?)\\s*(?:相似|类似|相近|差不多)\\s*(?:的)?\\s*(?:物料|材料|产品)?").matcher(t);
        if (m2.find()) return m2.group(1).trim();
        // 模式③：去除引导词，取主体
        String cleaned = t.replaceAll(
                "(?:请|帮(?:我)?|麻烦|我想|我想问|请问|推荐|找|查询|查|给我|列举|列出|有哪些|有啥|啥|哪些|相似|类似|相近|差不多|物料|材料|产品|推荐相似|类似)", " ")
                .trim();
        cleaned = cleaned.replaceAll("^(.*?)(?:相似|类似|相近).*$", "$1").trim();
        return cleaned;
    }

    /** 相似物料推荐结果 */
    @Data
    public static class SimilarMaterialResult {
        /** AI 综述回复文本 */
        private String reply;
        /** 召回的相似物料（供前端展示来源卡片） */
        private List<SimilarMaterialDTO> materials;
    }

    // ===================== 检索 =====================

    private static final Pattern LEVEL_ARABIC = Pattern.compile("(第?)(\\d{1,2})\\s*级");
    private static final Map<String, Integer> CN_NUM = new LinkedHashMap<>();

    static {
        CN_NUM.put("一", 1);
        CN_NUM.put("二", 2);
        CN_NUM.put("三", 3);
        CN_NUM.put("四", 4);
        CN_NUM.put("五", 5);
        CN_NUM.put("六", 6);
        CN_NUM.put("七", 7);
        CN_NUM.put("八", 8);
        CN_NUM.put("九", 9);
    }

    private List<CategoryEntity> retrieve(String question) {
        if (StrUtil.isBlank(question)) return Collections.emptyList();
        Set<Long> ids = new LinkedHashSet<>();

        // 1) 编码：精确命中则取子树+祖先；否则尝试前缀匹配
        Matcher m = CODE_PATTERN.matcher(question);
        while (m.find()) {
            String code = m.group();
            CategoryEntity exact = library.getByCode(code);
            if (exact != null) {
                ids.add(exact.getId());
                library.getSubtree(exact.getId()).forEach(c -> ids.add(c.getId()));
                library.getAncestors(exact.getId()).forEach(c -> ids.add(c.getId()));
            } else {
                boolean any = false;
                for (CategoryEntity c : library.getAllCategories()) {
                    if (c.getCategoryCode() != null && c.getCategoryCode().startsWith(code)) {
                        ids.add(c.getId());
                        any = true;
                    }
                }
                if (!any) {
                    log.debug("问题中的数字 {} 未匹配到标准分类编码，忽略", code);
                }
            }
        }

        // 2) 层级（支持阿拉伯数字与中文数字：一级/二级/三级/第一级/第1级/层级1）
        for (int lvl : detectLevels(question)) {
            library.getByLevel(lvl).forEach(c -> ids.add(c.getId()));
        }

        // 3) 关键词（去掉数字编码后的文本，避免编码干扰分词）
        String kw = question.replaceAll("\\d{2,6}", " ").trim();
        if (StrUtil.isNotBlank(kw)) {
            library.searchByKeyword(kw, topK).forEach(c -> ids.add(c.getId()));
        }

        // 4) 列举型问题（有哪些/所有/全部/列举）但未命中层级时，返回一级分类或全部（限量）
        if (ids.isEmpty() && isListingQuestion(question)) {
            List<CategoryEntity> listing = library.getByLevel(1);
            if (listing.isEmpty()) listing = library.getAllCategories();
            listing.stream().limit(maxContext).forEach(c -> ids.add(c.getId()));
        }

        // 5) 兜底：关键词未命中时，按问题分词做名称/编码子串模糊匹配，保证 AI 总有上下文
        if (ids.isEmpty() && StrUtil.isNotBlank(kw)) {
            fuzzyMatch(kw, ids);
        }

        List<CategoryEntity> list = new ArrayList<>();
        for (Long id : ids) {
            CategoryEntity c = library.getById(id);
            if (c != null) list.add(c);
        }
        list.sort((a, b) -> {
            int l = compareInt(a.getLevel(), b.getLevel());
            return l != 0 ? l : compareStr(a.getCategoryCode(), b.getCategoryCode());
        });
        if (list.size() > maxContext) list = new ArrayList<>(list.subList(0, maxContext));
        log.info("标准分类问答检索：问题=[{}]，命中 {} 条（全库 {} 条）", question, list.size(), library.size());
        return list;
    }

    /** 识别问题中的层级（阿拉伯数字 / 中文数字） */
    private Set<Integer> detectLevels(String q) {
        Set<Integer> levels = new LinkedHashSet<>();
        Matcher am = LEVEL_ARABIC.matcher(q);
        while (am.find()) {
            try {
                levels.add(Integer.parseInt(am.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher lm = Pattern.compile("层级\\s*(\\d{1,2})").matcher(q);
        while (lm.find()) {
            try {
                levels.add(Integer.parseInt(lm.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        for (Map.Entry<String, Integer> e : CN_NUM.entrySet()) {
            if (q.contains(e.getKey() + "级") || q.contains("第" + e.getKey() + "级")) {
                levels.add(e.getValue());
            }
        }
        return levels;
    }

    /** 是否为“列举/查询全部”类问题 */
    private boolean isListingQuestion(String q) {
        return q.contains("有哪些") || q.contains("有什么") || q.contains("全部分类")
                || q.contains("所有分类") || q.contains("列举") || q.contains("列出")
                || q.contains("都有什么") || q.contains("分类有哪些") || q.contains("分类有哪些")
                || q.contains("查询全部") || q.contains("全部标准") || q.contains("有哪些分类");
    }

    /** 分词后按子串模糊匹配分类名称/编码，作为检索兜底 */
    private void fuzzyMatch(String kw, Set<Long> ids) {
        for (String tok : tokenize(kw)) {
            if (tok.length() < 2) continue;
            for (CategoryEntity c : library.getAllCategories()) {
                if (ids.size() >= maxContext) return;
                String name = c.getCategoryName();
                if (name != null && name.contains(tok)) {
                    ids.add(c.getId());
                } else if (c.getCategoryCode() != null && c.getCategoryCode().contains(tok)) {
                    ids.add(c.getId());
                }
            }
        }
    }

    /** 与 CategoryStandardLibrary 一致的分词（按非字母数字中文切分） */
    private List<String> tokenize(String s) {
        if (s == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ===================== 提示词构建 =====================

    private String buildSystemPrompt(List<CategoryEntity> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.isBlank(categorySystemPrompt) ? DEFAULT_SYSTEM_PROMPT : categorySystemPrompt);
        sb.append("\n\n===== 标准分类库（main_data_category）相关记录（命中 ").append(context.size())
                .append(" 条，全库共 ").append(library.size()).append(" 条）=====\n");
        if (context.isEmpty()) {
            sb.append("（本次未检索到与问题直接相关的标准分类记录。标准分类库全库共 ").append(library.size())
                    .append(" 条，若用户的问题确实属于标准分类范畴，请基于你的常识礼貌说明，并提示用户可换一种表述，例如给出具体分类编码或分类名称。不要编造库中不存在的编码或名称。）\n");
        } else {
            for (CategoryEntity c : context) {
                sb.append(formatCategory(c)).append("\n");
            }
        }
        sb.append("============================================\n");
        return sb.toString();
    }

    private String formatCategory(CategoryEntity c) {
        StringBuilder sb = new StringBuilder();
        sb.append("[编码 ").append(c.getCategoryCode() == null ? "-" : c.getCategoryCode()).append("] ")
                .append(c.getCategoryName() == null ? "-" : c.getCategoryName())
                .append("（层级").append(c.getLevel() == null ? "-" : c.getLevel()).append("）")
                .append(" | 路径:").append(c.getFullPath() == null ? "-" : c.getFullPath());
        if (StrUtil.isNotBlank(c.getUnit())) sb.append(" | 单位:").append(c.getUnit());
        if (StrUtil.isNotBlank(c.getDescription())) {
            String desc = c.getDescription();
            if (desc.length() > 200) desc = desc.substring(0, 200) + "…";
            sb.append(" | 说明:").append(desc);
        }
        // 旧编码/旧名称
        List<String> oldPairs = new ArrayList<>();
        addOld(oldPairs, c.getOldCode1(), c.getOldName1());
        addOld(oldPairs, c.getOldCode2(), c.getOldName2());
        addOld(oldPairs, c.getOldCode3(), c.getOldName3());
        addOld(oldPairs, c.getOldCode4(), c.getOldName4());
        addOld(oldPairs, c.getOldCode5(), c.getOldName5());
        if (!oldPairs.isEmpty()) sb.append(" | 旧编码/名称:").append(String.join("; ", oldPairs));
        return sb.toString();
    }

    private void addOld(List<String> out, String code, String name) {
        if (StrUtil.isNotBlank(code) || StrUtil.isNotBlank(name)) {
            out.add((code == null ? "" : code) + "=" + (name == null ? "" : name));
        }
    }

    private int compareInt(Integer a, Integer b) {
        return Integer.compare(a == null ? 0 : a, b == null ? 0 : b);
    }

    private int compareStr(String a, String b) {
        return (a == null ? "" : a).compareTo(b == null ? "" : b);
    }

    /** 标准分类问答结果 */
    @Data
    public static class CategoryChatResult {
        /** AI 回复文本 */
        private String reply;
        /** 命中的标准分类来源（供前端展示） */
        private List<CategoryEntity> sources;
    }
}
