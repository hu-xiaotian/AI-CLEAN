package com.aiclean.match;

import com.aiclean.entity.CategoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对「祖先从硬局限改为全局加权」修复的单元测试。
 *
 * 不依赖数据库 / Spring 上下文，使用合成分类树 + 反射注入 SimilarityStrategy，
 * 直接验证 HierarchicalCategoryMatcher 的行为。
 */
class HierarchicalCategoryMatcherFixTest {

    private HierarchicalCategoryMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        matcher = new HierarchicalCategoryMatcher();
        // 注入无外部依赖的默认相似度策略（生产环境由 Spring 自动装配）
        Field f = HierarchicalCategoryMatcher.class.getDeclaredField("similarityStrategy");
        f.setAccessible(true);
        f.set(matcher, new DefaultSimilarityStrategy());
    }

    /** 构造一个含「跨子树误判」风险的黑色金属 / 有色金属分类树 */
    private List<CategoryEntity> buildTree() {
        List<CategoryEntity> all = new ArrayList<>();
        all.add(cat(1L, "1", "金属", null, 1, "1"));
        all.add(cat(2L, "10", "黑色金属", 1L, 2, "1/10"));
        all.add(cat(3L, "11", "有色金属", 1L, 2, "1/11"));
        // 黑色金属(错误子树) 下的 钢棒
        all.add(cat(4L, "100101", "钢棒", 2L, 3, "1/10/100101"));
        // 有色金属(正确子树) 下的 铜棒
        all.add(cat(5L, "110101", "铜棒", 3L, 3, "1/11/110101"));
        // 黑色金属 下的 铁合金（用于验证名称优先于编码）
        all.add(cat(6L, "100500", "铁合金", 2L, 3, "1/10/100500"));
        return all;
    }

    private CategoryEntity cat(Long id, String code, String name, Long parentId, int level, String fullPath) {
        CategoryEntity c = new CategoryEntity();
        c.setId(id);
        c.setCategoryCode(code);
        c.setCategoryName(name);
        c.setParentId(parentId);
        c.setLevel(level);
        c.setFullPath(fullPath);
        return c;
    }

    private CategoryMatchContext ctx(String name, String code, List<CategoryEntity> all) {
        CategoryMatchContext ctx = new CategoryMatchContext();
        ctx.setCategoryName(name);
        ctx.setCategoryCode(code);
        ctx.setAllCategories(all);
        ctx.setSynonyms(Collections.emptyList());
        return ctx;
    }

    /**
     * 核心验证：原始分类编码指向错误的祖先（"10" -> 黑色金属），
     * 但物料名称指向正确子树的「铜棒」。
     *
     * 旧逻辑（祖先硬局限）：候选集被锁在 黑色金属 子树 -> 只能命中 钢棒(编码前缀 0.6) -> 误判。
     * 新逻辑（全局加权）：铜棒 凭名称精确匹配(1.0) 在全局比较中胜出 -> 正确。
     */
    @Test
    void testWrongAncestorLockFixed() {
        List<CategoryEntity> all = buildTree();
        CategoryMatchOutcome out = matcher.match(ctx("铜棒", "10", all));

        assertNotNull(out.getCategory(), "应命中三级节点");
        assertEquals("铜棒", out.getCategory().getCategoryName(),
                "错误祖先锁定场景下，应返回正确子树的『铜棒』而非『钢棒』");
        assertEquals("110101", out.getCategory().getCategoryCode());

        // 对照：旧的两段式（祖先局限）逻辑在此场景下会返回 钢棒，打印出来供对比
        CategoryMatchOutcome legacy = legacyMatch(ctx("铜棒", "10", all));
        System.out.println("[对照] 旧逻辑(祖先硬局限)结果: "
                + (legacy.getCategory() == null ? "UNMATCHED" : legacy.getCategory().getCategoryName() + "/" + legacy.getSource()));
        System.out.println("[新逻辑] 结果: " + out.getCategory().getCategoryName() + "/" + out.getSource()
                + " confidence=" + out.getConfidence());
    }

    /** 回归：正常的名称+编码精确匹配在修复前后都应正确 */
    @Test
    void testNormalExactMatchUnchanged() {
        List<CategoryEntity> all = buildTree();
        CategoryMatchOutcome out = matcher.match(ctx("钢棒", "100101", all));
        assertNotNull(out.getCategory());
        assertEquals("钢棒", out.getCategory().getCategoryName());
        // 编码精确匹配会覆盖名称为 CODE_EXACT（名称/编码任一精确即可，分类结果正确）
        assertTrue("CODE_EXACT".equals(out.getSource()) || "NAME_EXACT".equals(out.getSource()),
                "正常精确匹配来源应为 CODE_EXACT 或 NAME_EXACT，实际: " + out.getSource());
    }

    /**
     * 验证名称优先于编码：数据编码指向“钢棒”(100101)，但名称是“铁合金”，
     * 应匹配名称命中的“铁合金”，而非编码精确的“钢棒”。
     */
    @Test
    void testNamePriorityOverCode() {
        List<CategoryEntity> all = buildTree();
        CategoryMatchOutcome out = matcher.match(ctx("铁合金", "100101", all));
        assertNotNull(out.getCategory());
        assertEquals("铁合金", out.getCategory().getCategoryName(),
                "名称与编码冲突时应优先按名称匹配");
        assertEquals("NAME_EXACT", out.getSource());

        // 对照：旧逻辑（编码可覆盖名称）在此场景下会返回 钢棒
        CategoryMatchOutcome legacy = legacyMatch(ctx("铁合金", "100101", all));
        System.out.println("[对照] 旧逻辑(编码优先)结果: "
                + (legacy.getCategory() == null ? "UNMATCHED" : legacy.getCategory().getCategoryName() + "/" + legacy.getSource()));
        System.out.println("[新逻辑] 结果: " + out.getCategory().getCategoryName() + "/" + out.getSource());
    }

    /** 不变量：任何命中的分类都必须是三级节点，且带有编码与完整路径 */
    @Test
    void testMatchedCategoryIsAlwaysLevel3() {
        List<CategoryEntity> all = buildTree();
        String[] names = {"铜棒", "钢棒", "不存在的物料"};
        for (String n : names) {
            CategoryMatchOutcome out = matcher.match(ctx(n, "10", all));
            if (out.getCategory() != null) {
                assertEquals(3, out.getCategory().getLevel(), "命中的必须是三级节点");
                assertNotNull(out.getCategory().getCategoryCode());
                assertNotNull(out.getCategory().getFullPath());
            } else {
                assertEquals("UNMATCHED", out.getSource());
            }
        }
    }

    /**
     * 复刻修复前的「祖先硬局限」两段式逻辑，仅用于对照演示。
     * 与 HierarchicalCategoryMatcher 当前实现保持独立，避免相互影响。
     */
    private CategoryMatchOutcome legacyMatch(CategoryMatchContext ctx) {
        List<CategoryEntity> all = ctx.getAllCategories();
        Map<Long, CategoryEntity> byId = new HashMap<>();
        Map<Long, List<CategoryEntity>> childrenByParent = new HashMap<>();
        List<CategoryEntity> l1 = new ArrayList<>(), l2 = new ArrayList<>(), l3 = new ArrayList<>();
        for (CategoryEntity c : all) {
            if (c.getId() != null) byId.put(c.getId(), c);
            if (c.getParentId() != null) childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            if (c.getLevel() != null) {
                if (c.getLevel() == 1) l1.add(c);
                else if (c.getLevel() == 2) l2.add(c);
                else if (c.getLevel() == 3) l3.add(c);
            }
        }
        String normName = normalize(ctx.getCategoryName());
        String normCode = normalizeCode(ctx.getCategoryCode());

        // 1. 定位祖先（旧阈值 0.5，实际只可能返回 0.0 或 ≥0.7）
        List<CategoryEntity> ancestors = new ArrayList<>();
        List<CategoryEntity> allAnc = new ArrayList<>();
        allAnc.addAll(l1); allAnc.addAll(l2);
        for (CategoryEntity anc : allAnc) {
            if (scoreAncestor(normName, normCode, anc) >= 0.5) ancestors.add(anc);
        }

        // 2. 局限候选集到祖先子树
        List<CategoryEntity> candidateL3 = new ArrayList<>();
        if (!ancestors.isEmpty()) {
            for (CategoryEntity anc : ancestors) candidateL3.addAll(getL3Descendants(anc, l3, childrenByParent));
        } else {
            candidateL3 = new ArrayList<>(l3);
        }
        CategoryMatchOutcome best = bestMatchL3(normName, normCode, candidateL3, null);
        if (best != null && best.getConfidence() >= 0.5) return best;

        // 3. 兜底全局
        if (!ancestors.isEmpty()) {
            CategoryMatchOutcome global = bestMatchL3(normName, normCode, l3, null);
            if (global != null && global.getConfidence() >= 0.5) return global;
        }
        return CategoryMatchOutcome.unmatched();
    }

    // ---- 以下为 legacyMatch 所需的精简副本（与生产代码逻辑一致）----
    private CategoryMatchOutcome bestMatchL3(String normName, String normCode,
                                             List<CategoryEntity> candidates, Map<Long, Double> boost) {
        CategoryEntity bestCat = null; double bestScore = 0.0; String bestSource = null;
        for (CategoryEntity cat : candidates) {
            double score = 0.0; String source = null;
            String catNorm = normalize(cat.getCategoryName());
            if (catNorm != null && normName != null) {
                if (catNorm.equals(normName)) { score = 1.0; source = "NAME_EXACT"; }
                else if (catNorm.contains(normName) || normName.contains(catNorm)) { score = 0.85; source = "NAME_FUZZY"; }
            }
            if (cat.getCategoryCode() != null && normCode != null) {
                if (normCode.equals(cat.getCategoryCode())) { score = 1.0; source = "CODE_EXACT"; }
                else if (cat.getCategoryCode().startsWith(normCode) && score < 0.6) { score = 0.6; source = "CODE_PREFIX"; }
            }
            if (score > 0 && boost != null) {
                Double b = boost.get(cat.getId());
                if (b != null) score = Math.min(1.0, score + b);
            }
            if (score > bestScore) { bestScore = score; bestCat = cat; bestSource = source; }
        }
        return bestCat == null ? null : new CategoryMatchOutcome(bestCat, bestSource, bestScore);
    }

    private double scoreAncestor(String normName, String normCode, CategoryEntity anc) {
        if (normName == null && normCode == null) return 0.0;
        String ancNorm = normalize(anc.getCategoryName());
        double best = 0.0;
        if (ancNorm != null && normName != null) {
            if (ancNorm.equals(normName)) best = Math.max(best, 1.0);
            else if (ancNorm.contains(normName) || normName.contains(ancNorm)) best = Math.max(best, 0.8);
        }
        if (normCode != null && anc.getCategoryCode() != null) {
            if (normCode.equals(anc.getCategoryCode())) best = Math.max(best, 1.0);
            else if (anc.getCategoryCode().startsWith(normCode)) best = Math.max(best, 0.7);
        }
        return best;
    }

    private List<CategoryEntity> getL3Descendants(CategoryEntity ancestor, List<CategoryEntity> l3,
                                                  Map<Long, List<CategoryEntity>> childrenByParent) {
        if (ancestor.getLevel() != null && ancestor.getLevel() == 3) return Collections.singletonList(ancestor);
        List<CategoryEntity> result = new ArrayList<>();
        Queue<CategoryEntity> q = new LinkedList<>(); q.add(ancestor);
        while (!q.isEmpty()) {
            CategoryEntity cur = q.poll();
            List<CategoryEntity> children = childrenByParent.get(cur.getId());
            if (children != null) for (CategoryEntity child : children) {
                if (child.getLevel() != null && child.getLevel() == 3) result.add(child);
                else q.add(child);
            }
        }
        return result;
    }

    private String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase().replaceAll("[\\(\\)（）\\[\\]【】\\{\\}/\\-\\s_]+", "");
        return n.isEmpty() ? null : n;
    }

    private String normalizeCode(String code) {
        if (code == null) return null;
        String n = code.trim().replaceAll("[\\s\\-_/]+", "");
        return n.isEmpty() ? null : n;
    }
}
