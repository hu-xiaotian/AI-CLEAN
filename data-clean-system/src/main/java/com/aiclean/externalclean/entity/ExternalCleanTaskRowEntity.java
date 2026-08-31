package com.aiclean.externalclean.entity;

import com.aiclean.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 外部清洗任务行实体
 * 同时承载：提交快照、清洗结果、采纳/修正记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("external_clean_task_row")
public class ExternalCleanTaskRowEntity extends BaseEntity {

    /** 关联任务号 */
    private String taskId;

    /** 行号，对应 RawRow.index */
    private Integer rowIndex;

    /** 源数据行ID（松引用，无外键） */
    private Long tempDataId;

    /** 提交的原始列数据快照(JSON: 列名->列值) */
    private String requestColumnsJson;

    // ===== 清洗结果（CleanResult）=====
    private String categoryCode;
    private String categoryName;
    private String categoryPath;
    private Double confidence;
    private Double categoryConfidence;
    private String extractedAttrsJson;
    private String missingAttrsJson;
    private Integer needsReview;
    private String reviewReason;

    /** 分类判定依据的引用片段(JSON数组) */
    private String categoryCitationsJson;

    /** 属性提取的引用片段(JSON对象: 属性名->引用) */
    private String attrCitationsJson;

    // ===== 行状态与采纳/修正 =====
    /** 行状态: pending/completed/skipped/accepted/corrected/rejected */
    private String rowStatus;

    private String correctedCategoryCode;
    private String correctedCategoryName;
    private String correctedAttrsJson;
    private String correctComment;

    private String operatedBy;
    private LocalDateTime operatedAt;
}
