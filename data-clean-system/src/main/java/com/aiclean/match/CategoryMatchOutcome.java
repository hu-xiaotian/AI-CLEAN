package com.aiclean.match;

import com.aiclean.entity.CategoryEntity;
import lombok.Data;

/**
 * 分类匹配结果。
 * 关键约束：category 永远是三级节点；未命中三级时 category 为 null（交由上层标记为无效数据）。
 */
@Data
public class CategoryMatchOutcome {

    /**
     * 命中的三级分类节点；未命中三级时为 null
     */
    private CategoryEntity category;

    /**
     * 匹配来源标识，便于质量评估与人工审核：
     * SYNONYM / NAME_EXACT / NAME_FUZZY / CODE_EXACT / CODE_PREFIX / EXTRA_NAME / SEMANTIC / UNMATCHED
     */
    private String source;

    /**
     * 置信度 0~1
     */
    private double confidence;

    public CategoryMatchOutcome() {
    }

    public CategoryMatchOutcome(CategoryEntity category, String source, double confidence) {
        this.category = category;
        this.source = source;
        this.confidence = confidence;
    }

    /**
     * 未命中三级节点（不赋一级/二级编码，交由无效数据页统计）
     */
    public static CategoryMatchOutcome unmatched() {
        return new CategoryMatchOutcome(null, "UNMATCHED", 0.0);
    }
}
