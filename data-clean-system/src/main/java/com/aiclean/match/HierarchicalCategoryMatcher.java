package com.aiclean.match;

import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CategorySynonymEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认分层分类匹配器。
 *
 * 匹配思路（满足“先定位一/二级大类，再在三级上匹配，最终必须落到三级节点”）：
 *  0) 同义词优先（人工维护，置信度最高）
 *  1) 用原始分类名称/编码，在一级、二级节点中定位“大类”（祖先），并记录其打分
 *  2) 在【全局】三级节点上综合多策略打分，命中祖先子树的三级获得“祖先加分”以占优，
 *     但不再被硬局限到某个子树（避免错误祖先把搜索锁死在错误子树而误判）：
 *     - 全词匹配（名称精确）
 *     - 模糊匹配（名称包含/被包含）
 *     - 语义相似度（SimilarityStrategy，可替换为真实语义模型）
 *     - 额外属性值辅助匹配（同属文本信号）
 *     - 编码精确 / 编码前缀匹配（仅作兜底：人工分类编码可能错误，名称优先）
 *     - 祖先加分（命中祖先子树的三级在全局比较中优先）
 *   名称/文本信号整体优先于编码信号：名称与编码冲突时按名称匹配。
 *  3) 全局最高分仍低于阈值 -> 返回 category=null（不赋一/二级编码，交由无效数据页统计）
 *
 * 本类不依赖任何业务表/Service，仅消费 CategoryMatchContext，便于整体替换为独立算法。
 */
@Component
public class HierarchicalCategoryMatcher implements CategoryMatcher {

    @Autowired
    private SimilarityStrategy similarityStrategy;

    /** 低于该置信度视为未命中三级节点 */
    private static final double MIN_CONFIDENCE = 0.5;

    /**
     * 祖先命中最低分：与 scoreAncestor 实际最低命中分（0.7，编码前缀）对齐，
     * 取代原先 0.5 的“死阈值”（scoreAncestor 只会返回 0.0 或 ≥0.7）。
     */
    private static final double ANCESTOR_MIN_SCORE = 0.7;

    /**
     * 祖先加分系数：命中祖先子树的三级在全局比较中获得的最高加分 = 祖先分 × 该系数。
     * 仅作为“偏好”信号，不改变匹配来源(source)，也不会把三级排除出候选集。
     */
    private static final double ANCESTOR_BOOST_FACTOR = 0.2;

    /**
     * 名称优先带：文本（名称 / 额外属性值）命中时的排序分统一高于“仅编码命中”的排序分，
     * 确保“名称与编码冲突时优先按名称匹配”。
     * 背景：原始数据多为人工分类，人工分类编码可能存在错误，而名称更可靠。
     * 取值需大于 ANCESTOR_BOOST_FACTOR，避免“编码命中 + 祖先加分”反超弱名称命中。
     */
    private static final double NAME_PRIORITY = 0.5;

    @Override
    public CategoryMatchOutcome match(CategoryMatchContext ctx) {
        if (ctx == null || ctx.getAllCategories() == null || ctx.getAllCategories().isEmpty()) {
            return CategoryMatchOutcome.unmatched();
        }

        List<CategoryEntity> all = ctx.getAllCategories();
        Map<Long, CategoryEntity> byId = all.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(CategoryEntity::getId, c -> c, (a, b) -> a));
        List<CategoryEntity> l1 = filterLevel(all, 1);
        List<CategoryEntity> l2 = filterLevel(all, 2);
        List<CategoryEntity> l3 = filterLevel(all, 3);
        Map<Long, List<CategoryEntity>> childrenByParent = all.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(CategoryEntity::getParentId));

        String rawName = ctx.getCategoryName();
        String normName = normalize(rawName);

        // 0. 同义词（最高优先级）
        CategoryMatchOutcome syn = matchBySynonym(ctx, l3, byId, childrenByParent);
        if (syn != null) {
            return syn;
        }

        // 1. 定位一级/二级祖先（大类）—— 仅作为“加分”信号，不硬局限三级候选集
        Map<CategoryEntity, Double> ancestors = findAncestors(rawName, ctx.getCategoryCode(), l1, l2);

        // 2. 计算祖先加分映射：命中祖先子树的三级在全局比较中占优，但不会被排除
        Map<Long, Double> ancestorBoost = computeAncestorBoost(ancestors, l3, childrenByParent);

        // 3. 在【全局】三级节点上统一打分（带祖先加分），一次定胜负。
        //    相比原“先局限子树、未命中再全局兜底”的两段式，本方案让正确子树内的三级
        //    能与其它子树的三级同场竞争，避免因错误祖先把搜索局限到错误子树而误判。
        CategoryMatchOutcome best = bestMatchL3(normName, ctx.getCategoryCode(), ctx.getExtraValues(), l3, ancestorBoost);
        if (best != null && best.getConfidence() >= MIN_CONFIDENCE) {
            return best;
        }

        // 4. 未命中三级节点：不赋一/二级编码
        return CategoryMatchOutcome.unmatched();
    }

    // ===================== 同义词 =====================

    private CategoryMatchOutcome matchBySynonym(CategoryMatchContext ctx, List<CategoryEntity> l3,
                                                Map<Long, CategoryEntity> byId,
                                                Map<Long, List<CategoryEntity>> childrenByParent) {
        if (ctx.getSynonyms() == null || ctx.getSynonyms().isEmpty()) return null;
        String normName = normalize(ctx.getCategoryName());
        if (normName == null) return null;

        for (CategorySynonymEntity syn : ctx.getSynonyms()) {
            if (syn.getCategoryId() == null || isBlank(syn.getSynonymName())) continue;
            String synNorm = isBlank(syn.getSynonymNorm()) ? normalize(syn.getSynonymName()) : syn.getSynonymNorm().trim().toLowerCase();
            if (!normName.equals(synNorm)) continue;

            CategoryEntity target = byId.get(syn.getCategoryId());
            if (target == null) continue;
            if (target.getLevel() != null && target.getLevel() == 3) {
                return new CategoryMatchOutcome(target, "SYNONYM", 0.95);
            }
            // 同义词指向一/二级：解析到其下三级
            List<CategoryEntity> descendants = getL3Descendants(target, l3, childrenByParent);
            if (!descendants.isEmpty()) {
                // 优先选名称与原始文本最贴近的三级，否则取第一个
                CategoryEntity best = pickByName(descendants, ctx.getCategoryName());
                return new CategoryMatchOutcome(best, "SYNONYM", 0.95);
            }
        }
        return null;
    }

    // ===================== 祖先定位 =====================

    /**
     * 定位一级/二级祖先（大类），返回命中的祖先及其打分。
     * 注意：祖先不再用于硬局限三级候选集，而是作为“加分”信号（见 computeAncestorBoost），
     * 让正确子树内的三级在全局比较中占优，同时不会被排除出候选。
     */
    private Map<CategoryEntity, Double> findAncestors(String rawName, String categoryCode,
                                                      List<CategoryEntity> l1, List<CategoryEntity> l2) {
        Map<CategoryEntity, Double> matched = new LinkedHashMap<>();
        List<CategoryEntity> ancestors = new ArrayList<>();
        ancestors.addAll(l1);
        ancestors.addAll(l2);

        String normName = normalize(rawName);
        String normCode = normalizeCode(categoryCode);

        for (CategoryEntity anc : ancestors) {
            double score = scoreAncestor(normName, normCode, anc);
            if (score >= ANCESTOR_MIN_SCORE) {
                matched.put(anc, score);
            }
        }
        return matched;
    }

    /**
     * 根据命中的祖先，计算每个三级节点应得的“祖先加分”。
     * 加分 = 祖先分 × ANCESTOR_BOOST_FACTOR；同一三级若被多个祖先覆盖则取最高加分。
     * 祖先加分只影响三级在全局比较中的相对优先级，不修改匹配来源(source)。
     */
    private Map<Long, Double> computeAncestorBoost(Map<CategoryEntity, Double> ancestors,
                                                   List<CategoryEntity> l3,
                                                   Map<Long, List<CategoryEntity>> childrenByParent) {
        Map<Long, Double> boost = new HashMap<>();
        if (ancestors == null || ancestors.isEmpty()) return boost;
        for (Map.Entry<CategoryEntity, Double> e : ancestors.entrySet()) {
            double b = e.getValue() * ANCESTOR_BOOST_FACTOR;
            for (CategoryEntity l3cat : getL3Descendants(e.getKey(), l3, childrenByParent)) {
                boost.merge(l3cat.getId(), b, Math::max);
            }
        }
        return boost;
    }

    private double scoreAncestor(String normName, String normCode, CategoryEntity anc) {
        if (normName == null && normCode == null) return 0.0;
        String ancNorm = normalize(anc.getCategoryName());
        double best = 0.0;
        if (ancNorm != null && normName != null) {
            if (ancNorm.equals(normName)) best = Math.max(best, 1.0);
            else if (ancNorm.contains(normName) || normName.contains(ancNorm)) best = Math.max(best, 0.8);
            else {
                double sim = similarityStrategy.similarity(normName, ancNorm);
                if (sim >= 0.7) best = Math.max(best, 0.6 + 0.3 * sim);
            }
        }
        if (normCode != null && anc.getCategoryCode() != null) {
            if (normCode.equals(anc.getCategoryCode())) best = Math.max(best, 1.0);
            else if (anc.getCategoryCode().startsWith(normCode)) best = Math.max(best, 0.7);
        }
        return best;
    }

    // ===================== 三级节点打分 =====================

    private CategoryMatchOutcome bestMatchL3(String normName, String categoryCode,
                                             List<String> extraValues, List<CategoryEntity> candidates,
                                             Map<Long, Double> ancestorBoost) {
        if (candidates == null || candidates.isEmpty()) return null;

        String normCode = normalizeCode(categoryCode);
        CategoryEntity bestCat = null;
        double bestScore = 0.0;   // 上报置信度（0~1，已封顶）
        double bestRank = -1.0;    // 排序分（含名称优先带，用于跨候选比较）
        String bestSource = null;

        for (CategoryEntity cat : candidates) {
            String catNorm = normalize(cat.getCategoryName());

            // ---- 文本信号（名称 + 额外属性值）：人工分类名称更可靠，优先于编码 ----
            double textScore = 0.0;
            String textSource = null;
            // 名称：全词 / 模糊 / 语义
            if (catNorm != null && normName != null) {
                if (catNorm.equals(normName)) {
                    textScore = 1.0; textSource = "NAME_EXACT";
                } else if (catNorm.contains(normName) || normName.contains(catNorm)) {
                    textScore = 0.85; textSource = "NAME_FUZZY";
                } else {
                    double sim = similarityStrategy.similarity(normName, catNorm);
                    if (sim >= 0.7) {
                        textScore = 0.6 + 0.3 * sim; textSource = "SEMANTIC";
                    }
                }
            }
            // 额外属性值辅助匹配（同属文本信号，优先于编码）
            if (extraValues != null) {
                for (String ev : extraValues) {
                    String evNorm = normalize(ev);
                    if (isBlank(evNorm)) continue;
                    if (catNorm != null && evNorm.equals(catNorm)) {
                        if (0.9 > textScore) { textScore = 0.9; textSource = "EXTRA_NAME"; }
                    } else if (catNorm != null && (catNorm.contains(evNorm) || evNorm.contains(catNorm))) {
                        if (0.8 > textScore) { textScore = 0.8; textSource = "EXTRA_NAME"; }
                    } else if (catNorm != null) {
                        double sim = similarityStrategy.similarity(evNorm, catNorm);
                        if (sim >= 0.7 && (0.6 + 0.3 * sim) > textScore) {
                            textScore = 0.6 + 0.3 * sim; textSource = "EXTRA_NAME";
                        }
                    }
                }
            }

            // ---- 编码信号：仅在文本信号缺失时作为兜底（人工分类编码可能错误）----
            double codeScore = 0.0;
            String codeSource = null;
            if (cat.getCategoryCode() != null) {
                if (categoryCode != null && categoryCode.equals(cat.getCategoryCode())) {
                    codeScore = 1.0; codeSource = "CODE_EXACT";
                } else if (normCode != null && cat.getCategoryCode().startsWith(normCode) && codeScore < 0.6) {
                    codeScore = 0.6; codeSource = "CODE_PREFIX";
                }
            }

            // 名称/文本优先：有文本命中则用文本分，否则用编码分
            boolean textWins = textScore > 0;
            double base;
            String source;
            if (textWins) {
                base = textScore; source = textSource;
            } else if (codeScore > 0) {
                base = codeScore; source = codeSource;
            } else {
                continue; // 文本与编码均无命中，不参与比较
            }

            // 排序分：文本命中整体高于编码命中（名称优先带），避免编码精确反超名称
            double rankScore = base + (textWins ? NAME_PRIORITY : 0.0);

            // 祖先加分：仅在该三级本身已有一定匹配时叠加，上限 1.0（用于上报置信度）
            if (ancestorBoost != null) {
                Double b = ancestorBoost.get(cat.getId());
                if (b != null) {
                    rankScore += b;
                    base = Math.min(1.0, base + b);
                }
            }

            if (rankScore > bestRank) {
                bestRank = rankScore;
                bestScore = base;
                bestCat = cat;
                bestSource = source;
            }
        }

        if (bestCat == null) return null;
        return new CategoryMatchOutcome(bestCat, bestSource, bestScore);
    }

    // ===================== 工具方法 =====================

    private List<CategoryEntity> filterLevel(List<CategoryEntity> all, int level) {
        return all.stream()
                .filter(c -> c.getLevel() != null && c.getLevel() == level)
                .collect(Collectors.toList());
    }

    /**
     * 获取某节点的三级后代（含自身若为三级）。
     */
    private List<CategoryEntity> getL3Descendants(CategoryEntity ancestor, List<CategoryEntity> l3,
                                                   Map<Long, List<CategoryEntity>> childrenByParent) {
        if (ancestor.getLevel() != null && ancestor.getLevel() == 3) {
            return Collections.singletonList(ancestor);
        }
        List<CategoryEntity> result = new ArrayList<>();
        Queue<CategoryEntity> queue = new LinkedList<>();
        queue.add(ancestor);
        while (!queue.isEmpty()) {
            CategoryEntity cur = queue.poll();
            List<CategoryEntity> children = childrenByParent.get(cur.getId());
            if (children != null) {
                for (CategoryEntity child : children) {
                    if (child.getLevel() != null && child.getLevel() == 3) {
                        result.add(child);
                    } else {
                        queue.add(child);
                    }
                }
            }
        }
        // 兜底：若按 parentId 未连上，用编码前缀匹配三级（兼容数据不规范场景）
        if (result.isEmpty() && isNotBlank(ancestor.getCategoryCode())) {
            for (CategoryEntity c : l3) {
                if (c.getCategoryCode() != null && c.getCategoryCode().startsWith(ancestor.getCategoryCode())) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private CategoryEntity pickByName(List<CategoryEntity> candidates, String rawName) {
        String norm = normalize(rawName);
        for (CategoryEntity c : candidates) {
            if (norm != null && norm.equals(normalize(c.getCategoryName()))) return c;
        }
        for (CategoryEntity c : candidates) {
            String cn = normalize(c.getCategoryName());
            if (cn != null && norm != null && (cn.contains(norm) || norm.contains(cn))) return c;
        }
        return candidates.get(0);
    }

    /**
     * 名称归一化：转小写、去空格、去除常见标点与括号，用于容错比较。
     */
    private String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        n = n.replaceAll("[\\(\\)（）\\[\\]【】\\{\\}/\\-\\s_]+", "");
        return n.isEmpty() ? null : n;
    }

    /**
     * 编码归一化：去空格、连字符、下划线、斜杠，用于编码前缀匹配容错。
     */
    private String normalizeCode(String code) {
        if (code == null) return null;
        String n = code.trim().replaceAll("[\\s\\-_/]+", "");
        return n.isEmpty() ? null : n;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isNotBlank(String s) {
        return !isBlank(s);
    }
}
