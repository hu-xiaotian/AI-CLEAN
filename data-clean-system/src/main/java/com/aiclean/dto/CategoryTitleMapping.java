package com.aiclean.dto;

import lombok.Data;

/**
 * 数据文件下"已清洗分类 -> 标准字段表头"的映射视图
 * 用于属性补全模块提前编辑分类映射
 */
@Data
public class CategoryTitleMapping {
    /**
     * 分类编码（清洗后数据中的 category_code）
     */
    private String categoryCode;

    /**
     * 分类名称（由分类编码反查得到）
     */
    private String categoryName;

    /**
     * 该分类下的数据条数
     */
    private Integer count;

    /**
     * 当前已关联的标准字段表头ID（未关联为 null）
     */
    private Long standardTitleId;

    /**
     * 当前已关联的标准字段表头名称
     */
    private String standardTitleName;
}
