package com.aiclean.service;

import cn.hutool.core.util.StrUtil;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CategorySynonymEntity;
import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.mapper.CategoryMapper;
import com.aiclean.mapper.CategorySynonymMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主数据分类标准库（main_data_category 全表）。
 *
 * 作用：把整张 main_data_category（含旧编码/旧名称、同义词）加载进内存并建立索引，
 * 为“分类准确性判定”提供两件事：
 *   1) 候选召回 retrieveCandidates：从全表候选池中按编码/名称/分词召回 top-K 相关标准分类；
 *   2) 规则校验 ruleCheck：不依赖 AI，确定性地把系统分类与标准库比对，给出准确性评分与一致性判定。
 *
 * 整表不会逐条丢给大模型；AI 模式只把“召回的候选子集”发给模型，控制 token 与成本。
 */
@Service
@Slf4j
public class CategoryStandardLibrary {

    private final CategoryMapper categoryMapper;
    private final CategorySynonymMapper categorySynonymMapper;

    @Value("${app.data-cleaning.standard-library.candidate-top-k:10}")
    private int candidateTopK;

    // ===================== 内存索引 =====================
    private volatile List<CategoryEntity> allCategories = new ArrayList<>();
    private volatile Map<Long, CategoryEntity> byId = new HashMap<>();
    /** 归一化编码（含旧编码1~5） -> 标准分类 */
    private volatile Map<String, CategoryEntity> codeIndex = new HashMap<>();
    /** 归一化名称（含旧名称1~5） -> 标准分类列表 */
    private volatile Map<String, List<CategoryEntity>> nameIndex = new HashMap<>();
    /** 名称分词 -> 标准分类列表（用于模糊匹配） */
    private volatile Map<String, List<CategoryEntity>> tokenIndex = new HashMap<>();
    private volatile boolean loaded = false;

    public CategoryStandardLibrary(CategoryMapper categoryMapper, CategorySynonymMapper categorySynonymMapper) {
        this.categoryMapper = categoryMapper;
        this.categorySynonymMapper = categorySynonymMapper;
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("标准库初始化失败，将在首次使用时重试", e);
        }
    }

    /**
     * 重新从数据库全量加载并构建索引（标准库更新后可手动调用）。
     */
    public synchronized void reload() {
        List<CategoryEntity> cats = categoryMapper.selectList(null);
        List<CategorySynonymEntity> syns = categorySynonymMapper.selectList(null);

        Map<Long, CategoryEntity> idMap = new HashMap<>();
        Map<String, CategoryEntity> cIdx = new HashMap<>();
        Map<String, List<CategoryEntity>> nIdx = new HashMap<>();
        Map<String, List<CategoryEntity>> tIdx = new HashMap<>();

        for (CategoryEntity c : cats) {
            if (c.getId() != null) idMap.put(c.getId(), c);
            addCode(cIdx, c.getCategoryCode(), c);
            addCode(cIdx, c.getOldCode1(), c);
            addCode(cIdx, c.getOldCode2(), c);
            addCode(cIdx, c.getOldCode3(), c);
            addCode(cIdx, c.getOldCode4(), c);
            addCode(cIdx, c.getOldCode5(), c);

            addName(nIdx, c.getCategoryName(), c);
            addName(nIdx, c.getOldName1(), c);
            addName(nIdx, c.getOldName2(), c);
            addName(nIdx, c.getOldName3(), c);
            addName(nIdx, c.getOldName4(), c);
            addName(nIdx, c.getOldName5(), c);

            addTokens(tIdx, c.getCategoryName(), c);
            addTokens(tIdx, c.getOldName1(), c);
            addTokens(tIdx, c.getOldName2(), c);
            addTokens(tIdx, c.getOldName3(), c);
            addTokens(tIdx, c.getOldName4(), c);
            addTokens(tIdx, c.getOldName5(), c);
        }

        // 同义词：映射到其指向的标准分类
        if (syns != null) {
            for (CategorySynonymEntity s : syns) {
                if (s.getCategoryId() == null) continue;
                CategoryEntity target = idMap.get(s.getCategoryId());
                if (target == null) continue;
                addName(nIdx, s.getSynonymName(), target);
                addTokens(tIdx, s.getSynonymName(), target);
            }
        }

        this.allCategories = cats;
        this.byId = idMap;
        this.codeIndex = cIdx;
        this.nameIndex = nIdx;
        this.tokenIndex = tIdx;
        this.loaded = true;
        log.info("标准库加载完成：分类 {} 条，同义词 {} 条", cats.size(), syns == null ? 0 : syns.size());
    }

    public void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) reload();
            }
        }
    }

    /** 按编码（含旧编码）从内存标准库查询标准分类，避免逐行 DB 查询 */
    public CategoryEntity getByCode(String code) {
        ensureLoaded();
        return codeIndex.get(normalizeCode(code));
    }

    /** 按主键查询标准分类 */
    public CategoryEntity getById(Long id) {
        ensureLoaded();
        return id == null ? null : byId.get(id);
    }

    /** 返回全部标准分类（副本，避免外部修改索引） */
    public List<CategoryEntity> getAllCategories() {
        ensureLoaded();
        return new ArrayList<>(allCategories);
    }

    /** 返回标准库当前条数（用于诊断/提示词） */
    public int size() {
        ensureLoaded();
        return allCategories.size();
    }

    /** 按层级返回标准分类（如 level=1 返回所有一级分类），按编码排序 */
    public List<CategoryEntity> getByLevel(int level) {
        ensureLoaded();
        List<CategoryEntity> r = new ArrayList<>();
        for (CategoryEntity c : allCategories) {
            if (c.getLevel() != null && c.getLevel() == level) r.add(c);
        }
        r.sort(Comparator.comparing(c -> c.getCategoryCode() == null ? "" : c.getCategoryCode()));
        return r;
    }

    /** 按父节点返回直接子分类，按排序/编码排序 */
    public List<CategoryEntity> getChildren(Long parentId) {
        ensureLoaded();
        List<CategoryEntity> r = new ArrayList<>();
        for (CategoryEntity c : allCategories) {
            if (Objects.equals(c.getParentId(), parentId)) r.add(c);
        }
        r.sort((a, b) -> {
            int so = compareInt(a.getSortOrder(), b.getSortOrder());
            return so != 0 ? so : compareStr(a.getCategoryCode(), b.getCategoryCode());
        });
        return r;
    }

    /**
     * 关键词检索标准分类（编码精确/前缀 + 名称精确 + 分词重叠），
     * 用于把用户自然语言问题映射到标准库中的相关记录。返回去重后的前 limit 条。
     */
    public List<CategoryEntity> searchByKeyword(String keyword, int limit) {
        ensureLoaded();
        if (StrUtil.isBlank(keyword)) return new ArrayList<>();
        String raw = keyword.trim();
        String normKw = normalize(raw);
        Set<Long> ids = new LinkedHashSet<>();
        // 编码精确（含旧编码）
        CategoryEntity byCode = getByCode(raw);
        if (byCode != null) ids.add(byCode.getId());
        // 编码前缀
        for (CategoryEntity c : allCategories) {
            if (c.getCategoryCode() != null && c.getCategoryCode().startsWith(raw)) ids.add(c.getId());
        }
        // 名称精确 + 分词
        if (normKw != null) {
            List<CategoryEntity> byName = nameIndex.get(normKw);
            if (byName != null) byName.forEach(c -> ids.add(c.getId()));
            for (String tok : tokenize(normKw)) {
                List<CategoryEntity> byTok = tokenIndex.get(tok);
                if (byTok != null) byTok.forEach(c -> ids.add(c.getId()));
            }
        }
        List<CategoryEntity> result = new ArrayList<>();
        for (Long id : ids) {
            CategoryEntity c = byId.get(id);
            if (c != null) result.add(c);
        }
        if (result.size() > limit) result = result.subList(0, limit);
        return result;
    }

    /** 返回某分类的完整祖先链（含自身），从根到当前节点 */
    public List<CategoryEntity> getAncestors(Long id) {
        ensureLoaded();
        List<CategoryEntity> chain = new ArrayList<>();
        CategoryEntity cur = getById(id);
        Set<Long> guard = new HashSet<>();
        while (cur != null && guard.add(cur.getId())) {
            chain.add(0, cur);
            cur = cur.getParentId() == null ? null : getById(cur.getParentId());
        }
        return chain;
    }

    /** 返回某分类的整棵子树（含自身），先序遍历 */
    public List<CategoryEntity> getSubtree(Long id) {
        ensureLoaded();
        List<CategoryEntity> out = new ArrayList<>();
        collectSubtree(id, out);
        return out;
    }

    private void collectSubtree(Long id, List<CategoryEntity> out) {
        CategoryEntity root = getById(id);
        if (root == null) return;
        out.add(root);
        for (CategoryEntity c : allCategories) {
            if (Objects.equals(c.getParentId(), id)) collectSubtree(c.getId(), out);
        }
    }

    private int compareInt(Integer a, Integer b) {
        int x = a == null ? 0 : a;
        int y = b == null ? 0 : b;
        return Integer.compare(x, y);
    }

    private int compareStr(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.compareTo(y);
    }

    // ===================== 候选召回 =====================

    /**
     * 从全表候选池中召回与物料最相关的 top-K 标准分类。
     * 仅做哈希索引查找（编码精确、名称精确、分词重叠），复杂度为 O(文本段数)，可安全用于批量。
     */
    public List<Candidate> retrieveCandidates(CleanedDataEntity material, int topK) {
        ensureLoaded();
        Map<Long, Double> scoreMap = new HashMap<>();
        scoreCode(scoreMap, material != null ? material.getCategoryCode() : null, 1.0);
        scoreCode(scoreMap, material != null ? material.getMaterialCode() : null, 0.8);
        scoreText(scoreMap, material != null ? material.getCategoryName() : null, 0.9);
        // 属性拆分列(全描述)是最丰富的匹配信号：权重最高，使 AI 打分基于导入指定的属性拆分列，而非仅属性名称列
        scoreText(scoreMap, material != null ? material.getFullDescription() : null, 0.95);
        scoreText(scoreMap, material != null ? material.getMaterialName() : null, 0.85);
        scoreText(scoreMap, material != null ? material.getSpecification() : null, 0.6);
        scoreText(scoreMap, material != null ? material.getGrade() : null, 0.5);

        List<Candidate> list = new ArrayList<>();
        for (Map.Entry<Long, Double> e : scoreMap.entrySet()) {
            CategoryEntity cat = byId.get(e.getKey());
            if (cat != null) list.add(new Candidate(cat, e.getValue()));
        }
        list.sort((a, b) -> Double.compare(b.relevance, a.relevance));
        return list.subList(0, Math.min(topK, list.size()));
    }

    // ===================== 规则校验（无需 AI） =====================

    /**
     * 确定性地把系统分类与标准库比对，给出评分、一致性判定与说明。
     * 评分维度：编码合法性(35) + 名称一致性(30) + 层级合法(15/8/3) + 单位一致(10) + 描述关键词(10)。
     */
    public RuleCheck ruleCheck(CleanedDataEntity material, CategoryEntity matched) {
        ensureLoaded();
        RuleCheck rc = new RuleCheck();
        if (allCategories.isEmpty()) {
            rc.score = 50.0;
            rc.consistent = matched != null;
            rc.reason = "标准库未加载，使用中性评分";
            rc.bestMatchCode = matched != null ? matched.getCategoryCode() : null;
            rc.bestMatchName = matched != null ? matched.getCategoryName() : null;
            return rc;
        }
        if (matched == null) {
            rc.score = 20.0;
            rc.consistent = false;
            rc.reason = "系统分类未命中标准库（无对应标准分类）";
            return rc;
        }
        boolean codeOk = codeIndex.containsKey(normalizeCode(material != null ? material.getCategoryCode() : null));
        boolean nameOk = nameMatches(material, matched);
        boolean unitOk = unitConsistent(material != null ? material.getUnit() : null, matched.getUnit());
        boolean descOk = descKeywordHit(material, matched);

        double score = 0;
        if (codeOk) score += 35;
        if (nameOk) score += 30;
        if (matched.getLevel() != null) {
            if (matched.getLevel() == 3) score += 15;
            else if (matched.getLevel() == 2) score += 8;
            else score += 3;
        }
        if (unitOk) score += 10;
        if (descOk) score += 10;
        score = Math.max(0, Math.min(score, 100));

        rc.score = score;
        rc.consistent = codeOk && nameOk;
        rc.bestMatchCode = matched.getCategoryCode();
        rc.bestMatchName = matched.getCategoryName();
        rc.reason = String.format("编码%s，名称%s，层级%s，单位%s，描述关键词%s",
                codeOk ? "一致" : "不一致",
                nameOk ? "一致" : "不一致",
                matched.getLevel(),
                unitOk ? "一致" : "不一致/空",
                descOk ? "命中" : "未命中");
        return rc;
    }

    /** 规则校验评分（便捷方法，供质量评分流程使用） */
    public double ruleBasedAccuracy(CleanedDataEntity material, CategoryEntity matched) {
        return ruleCheck(material, matched).score;
    }

    // ===================== 私有工具 =====================

    private void addCode(Map<String, CategoryEntity> idx, String code, CategoryEntity c) {
        String n = normalizeCode(code);
        if (n != null && !idx.containsKey(n)) idx.put(n, c);
    }

    private void addName(Map<String, List<CategoryEntity>> idx, String name, CategoryEntity c) {
        String n = normalize(name);
        if (n == null) return;
        idx.computeIfAbsent(n, k -> new ArrayList<>()).add(c);
    }

    private void addTokens(Map<String, List<CategoryEntity>> idx, String name, CategoryEntity c) {
        String n = normalize(name);
        if (n == null) return;
        for (String tok : tokenize(n)) {
            idx.computeIfAbsent(tok, k -> new ArrayList<>()).add(c);
        }
    }

    private void scoreCode(Map<Long, Double> scoreMap, String code, double weight) {
        String n = normalizeCode(code);
        if (n == null) return;
        CategoryEntity c = codeIndex.get(n);
        if (c != null && c.getId() != null) bump(scoreMap, c.getId(), weight);
    }

    private void scoreText(Map<Long, Double> scoreMap, String text, double weight) {
        String n = normalize(text);
        if (n == null) return;
        List<CategoryEntity> byName = nameIndex.get(n);
        if (byName != null) {
            for (CategoryEntity c : byName) if (c.getId() != null) bump(scoreMap, c.getId(), weight);
        }
        for (String tok : tokenize(n)) {
            List<CategoryEntity> byTok = tokenIndex.get(tok);
            if (byTok != null) {
                for (CategoryEntity c : byTok) if (c.getId() != null) bump(scoreMap, c.getId(), weight * 0.6);
            }
        }
    }

    private void bump(Map<Long, Double> scoreMap, Long id, double v) {
        scoreMap.merge(id, v, Math::max);
    }

    private boolean nameMatches(CleanedDataEntity material, CategoryEntity matched) {
        if (material == null || matched == null) return false;
        String sysName = normalize(material.getCategoryName());
        String matName = normalize(material.getMaterialName());
        String stdName = normalize(matched.getCategoryName());

        if (sysName != null && stdName != null && (sysName.equals(stdName) || contains(sysName, stdName))) return true;
        if (sysName != null && oldNameEquals(matched, sysName)) return true;
        if (matName != null && stdName != null && (matName.equals(stdName) || contains(matName, stdName))) return true;

        // 分词重叠：物料名称与标准名称存在共同关键词
        if (matName != null && stdName != null) {
            Set<String> stdTokens = new HashSet<>(tokenize(stdName));
            for (String t : tokenize(matName)) {
                if (stdTokens.contains(t)) return true;
            }
        }
        return false;
    }

    /** 中文短语无分词，做包含判断：短串（>=2字符）是长串子串即视为相关，避免“钢板”被误判不等于“碳素结构花纹钢板” */
    private boolean contains(String a, String b) {
        if (a == null || b == null) return false;
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        return shorter.length() >= 2 && longer.contains(shorter);
    }

    private boolean oldNameEquals(CategoryEntity matched, String norm) {
        return norm.equals(normalize(matched.getOldName1()))
                || norm.equals(normalize(matched.getOldName2()))
                || norm.equals(normalize(matched.getOldName3()))
                || norm.equals(normalize(matched.getOldName4()))
                || norm.equals(normalize(matched.getOldName5()));
    }

    private boolean unitConsistent(String a, String b) {
        if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) return true; // 一方为空不矛盾
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private boolean descKeywordHit(CleanedDataEntity material, CategoryEntity matched) {
        if (matched.getDescription() == null) return false;
        String desc = normalize(matched.getDescription());
        if (desc == null) return false;
        for (String field : new String[]{material.getMaterialName(), material.getSpecification(), material.getGrade()}) {
            String n = normalize(field);
            if (n == null) continue;
            for (String tok : tokenize(n)) {
                if (desc.contains(tok)) return true;
            }
        }
        return false;
    }

    /** 名称归一化：转小写、去空格与常见标点括号，用于容错比较 */
    private String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        n = n.replaceAll("[\\(\\)（）\\[\\]【】\\{\\}/\\-\\s_]+", "");
        return n.isEmpty() ? null : n;
    }

    /** 编码归一化：去空格、连字符、下划线、斜杠 */
    private String normalizeCode(String code) {
        if (code == null) return null;
        String n = code.trim().replaceAll("[\\s\\-_/]+", "");
        return n.isEmpty() ? null : n;
    }

    /** 分词：按非（字母/数字/中文）切分 */
    private List<String> tokenize(String s) {
        if (s == null) return Collections.emptyList();
        return Arrays.stream(s.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    // ===================== 数据结构 =====================

    /** 候选标准分类（含相关性打分） */
    @Data
    public static class Candidate {
        private final CategoryEntity category;
        private final double relevance;

        public Candidate(CategoryEntity category, double relevance) {
            this.category = category;
            this.relevance = relevance;
        }
    }

    /** 规则校验结果 */
    @Data
    public static class RuleCheck {
        /** 准确性评分 0~100 */
        private double score;
        /** 系统分类是否与标准库一致 */
        private boolean consistent;
        /** 最合理标准编码（规则模式下即系统命中编码） */
        private String bestMatchCode;
        /** 最合理标准名称 */
        private String bestMatchName;
        /** 说明 */
        private String reason;
    }
}
