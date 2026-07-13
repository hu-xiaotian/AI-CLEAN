package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 填充失败结果数据实体
 * 记录因未匹配到标准字段表头（standard_title_id 非空约束）等原因而未能写入 result_data 的数据，
 * 便于在页面上展示“填充失败列表”，而不影响其余数据的正常填充。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("failed_result_data")
public class FailedResultDataEntity extends BaseEntity {

    /** 关联原始数据ID */
    private Long tempDataId;

    /** 关联清洗后数据ID */
    private Long cleanedDataId;

    /** 导致失败的分类编码 */
    private String categoryCode;

    /** 失败原因 */
    private String reason;

    /** 原始数据快照（col1..col10 拼接），便于在页面回看 */
    private String rawData;

    /** 状态，固定为 FAILED */
    private String status;
}
