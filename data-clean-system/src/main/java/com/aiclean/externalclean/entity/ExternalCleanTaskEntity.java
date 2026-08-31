package com.aiclean.externalclean.entity;

import com.aiclean.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 外部清洗任务实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("external_clean_task")
public class ExternalCleanTaskEntity extends BaseEntity {

    /** 任务号 task-{YYYYMMDD}-{seq}，与外部服务交互唯一标识 */
    private String taskId;

    /** 来源数据文件ID（松引用，无外键） */
    private Long tempDataTitleId;

    /** 来源文件名快照 */
    private String fileName;

    /** 提交模式: sync/async */
    private String mode;

    /** 状态: pending/submitting/processing/completed/failed/cancelled/callback_timeout */
    private String status;

    /** 下发给外部服务的回调地址 */
    private String callbackUrl;

    private Integer totalRows;

    /** 已分类行数 */
    private Integer classifiedRows;

    private Integer processedRows;
    private Integer highConfidence;
    private Integer mediumConfidence;
    private Integer lowConfidence;

    /** 置信度累加值，用于计算平均置信度 */
    private Double confidenceSum;

    private Double estimatedAccuracy;

    /** 提交时的清洗选项快照(CleanOptions JSON) */
    private String optionsJson;

    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime callbackReceivedAt;

    private String errorMessage;
    private Integer retryCount;
}
