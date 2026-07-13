package com.aiclean.match;

/**
 * 分类匹配器接口。
 * 实现应做到与业务无关、可独立替换：将来可接入语义向量/远程算法，只需提供新的实现并声明为 Spring Bean。
 */
public interface CategoryMatcher {

    /**
     * 根据输入上下文匹配分类。
     *
     * @param context 匹配上下文（分类名称/编码/全量分类树/同义词等）
     * @return 匹配结果；未命中三级节点时 category 为 null
     */
    CategoryMatchOutcome match(CategoryMatchContext context);
}
