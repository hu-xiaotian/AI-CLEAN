package com.aiclean.dto;

import lombok.Data;

/**
 * AI 辅助分类检测的单条明细（基于 main_data_category 标准库比对）。
 */
@Data
public class ClassifyCheckDetail {
    /** 清洗数据ID */
    private Long id;
    /** 物料代码 */
    private String materialCode;
    /** 物料名称 */
    private String materialName;
    /** 系统分类编码 */
    private String categoryCode;
    /** 系统分类名称 */
    private String categoryName;
    /** 准确性评分 0~100 */
    private Double score;
    /** 系统分类是否与标准库一致 */
    private Boolean matched;
    /** 最合理标准编码（不一致时给出 AI 建议） */
    private String bestMatchCode;
    /** 最合理标准名称 */
    private String bestMatchName;
    /** 说明 */
    private String reason;
    /** 召回的候选标准分类数量 */
    private Integer candidateCount;
}
