package com.aiclean.dto;

import lombok.Data;

/**
 * 一次性批次分类的单条结果。
 * <p>
 * 大模型一次性对一批物料返回一个 JSON 数组，每项对应一行物料，
 * 解析后按清洗数据 id 写回 cleaned_data。
 */
@Data
public class BatchClassifyResult {
    /** 清洗数据ID（对应输入批次中的 id） */
    private Long id;
    /** 标准分类编码 */
    private String categoryCode;
    /** 标准分类名称 */
    private String categoryName;
    /** 准确性评分 0~100 */
    private Double score;
    /** 理由说明 */
    private String reason;
    /** 是否成功写库（由服务层填充） */
    private Boolean persisted;
    /** 失败原因（persisted=false 时填充） */
    private String error;
}
