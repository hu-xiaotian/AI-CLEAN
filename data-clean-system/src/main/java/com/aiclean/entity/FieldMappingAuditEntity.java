package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 字段映射审核实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("field_mapping_audit")
public class FieldMappingAuditEntity extends BaseEntity {
    
    /**
     * 标准字段表头ID
     */
    private Long standardTitleId;
    
    /**
     * 原始表头ID
     */
    private Long tempDataTitleId;
    
    /**
     * 源数据类型（temp_data, extra_data）
     */
    private String sourceType;
    
    /**
     * 源字段名
     */
    private String sourceField;
    
    /**
     * 目标字段名
     */
    private String targetField;
    
    /**
     * 映射类型（auto, manual, suggested）
     */
    private String mappingType;
    
    /**
     * 置信度（系统匹配的置信度）
     */
    private Double confidence;
    
    /**
     * 审核状态（pending, approved, rejected, modified）
     */
    private String status;
    
    /**
     * 建议的目标字段（审核时可修改）
     */
    private String suggestedTargetField;
    
    /**
     * 审核意见
     */
    private String reviewComment;
    
    /**
     * 审核人
     */
    private String reviewedBy;
    
    /**
     * 审核时间
     */
    private LocalDateTime reviewedAt;
    
    /**
     * 原始表头（非数据库字段）
     */
    @TableField(exist = false)
    private TempDataTitleEntity tempDataTitle;
    
    /**
     * 是否已审核
     */
    public boolean isReviewed() {
        return !"pending".equals(status);
    }
    
    /**
     * 是否通过
     */
    public boolean isApproved() {
        return "approved".equals(status) || "modified".equals(status);
    }
    
    /**
     * 是否需要修改
     */
    public boolean needsModification() {
        return "modified".equals(status);
    }
}