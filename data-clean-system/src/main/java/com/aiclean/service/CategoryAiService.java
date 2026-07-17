package com.aiclean.service;

import cn.hutool.core.util.StrUtil;
import com.aiclean.ai.AiClientService;
import com.aiclean.entity.CategoryEntity;
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

    @Value("${app.ai.category-top-k:15}")
    private int topK;

    @Value("${app.ai.category-max-context:40}")
    private int maxContext;

    @Value("${app.ai.category-system-prompt:}")
    private String categorySystemPrompt;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一名“标准分类代码查询助手”，专门基于下方提供的【标准分类库（main_data_category）】数据回答用户关于标准分类的问题。\n" +
            "规则：\n" +
            "1. 只能依据【标准分类库】中给出的记录作答，不得编造库中不存在的分类编码或名称。\n" +
            "2. 若用户给出一段物料名称/描述并询问应归入哪个标准分类，请在库中检索最匹配的分类，给出分类编码与名称，并说明理由。\n" +
            "3. 若库中确实没有相关信息，明确告知“标准分类库中未找到相关记录”，不要臆测。\n" +
            "4. 回答使用简洁中文；涉及分类时附上分类编码（如 100101）与完整路径（full_path）。\n" +
            "5. 可以列举、对比、解释分类的层级关系、计量单位、说明等字段。";

    public CategoryAiService(CategoryStandardLibrary library, AiClientService aiClientService) {
        this.library = library;
        this.aiClientService = aiClientService;
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
