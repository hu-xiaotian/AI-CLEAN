package com.aiclean.externalclean.dto;

import lombok.Data;

/**
 * 清洗选项（与 api-design.md CleanOptions 对齐）
 */
@Data
public class CleanOptions {

    /** 置信度阈值 0~1，低于此值标记需人工复核 */
    private Double threshold = 0.7;

    /** 向量检索返回给 LLM 的候选分类数 1~50 */
    private Integer maxCandidates = 10;

    /** 模型别名 */
    private String model = "default";
}
