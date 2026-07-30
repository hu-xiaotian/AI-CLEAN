package com.aiclean.externalclean.entity;

import com.aiclean.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 外部清洗回调日志实体（幂等 + 审计）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("external_clean_callback_log")
public class ExternalCleanCallbackLogEntity extends BaseEntity {

    private String taskId;
    private String callbackStatus;
    private Integer pageNo;
    private Integer pageSize;
    /** 回调体SHA-256摘要（幂等去重） */
    private String payloadDigest;
    /** 原始回调报文快照 */
    private String payloadSnapshot;
    /** 处理结果: success/duplicate/invalid/error */
    private String processResult;
    private String errorMessage;
    private LocalDateTime receivedAt;
}
