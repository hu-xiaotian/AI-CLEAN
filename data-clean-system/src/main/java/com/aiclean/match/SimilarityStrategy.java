package com.aiclean.match;

/**
 * 文本相似度策略接口。
 * 默认实现为字符级相似度（占位），后续可替换为基于词向量/语义模型的实现，
 * 只要实现该接口并声明为 Spring Bean 即可被 {@link HierarchicalCategoryMatcher} 使用。
 */
public interface SimilarityStrategy {

    /**
     * 计算两段文本的相似度，返回 0~1，1 表示完全相同。
     */
    double similarity(String a, String b);
}
