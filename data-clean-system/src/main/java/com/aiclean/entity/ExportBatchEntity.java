package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 导出批次实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("export_batch")
public class ExportBatchEntity extends BaseEntity {
    
    /**
     * 批次名称
     */
    private String batchName;
    
    /**
     * 导出类型（by_category, by_status, custom）
     */
    private String exportType;
    
    /**
     * 筛选条件（JSON）
     */
    private String filterConditions;
    
    /**
     * 分类ID列表（JSON）
     */
    private String categoryIds;
    
    /**
     * 状态列表（JSON）
     */
    private String statusList;
    
    /**
     * 导出格式（excel, csv, json）
     */
    private String format;
    
    /**
     * 包含的列（JSON）
     */
    private String includeColumns;
    
    /**
     * 文件名
     */
    private String fileName;
    
    /**
     * 文件路径
     */
    private String filePath;
    
    /**
     * 文件大小
     */
    private Long fileSize;
    
    /**
     * 总记录数
     */
    private Integer totalRecords;
    
    /**
     * 已导出记录数
     */
    private Integer exportedRecords;
    
    /**
     * 状态（pending, processing, completed, failed）
     */
    private String status;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 导出人
     */
    private String exportedBy;
    
    /**
     * 导出时间
     */
    private LocalDateTime exportedAt;
    
    /**
     * 导出进度（非数据库字段）
     */
    @TableField(exist = false)
    private Double progress;
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }
    
    /**
     * 是否失败
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }
    
    /**
     * 是否处理中
     */
    public boolean isProcessing() {
        return "processing".equals(status);
    }
    
    /**
     * 计算导出进度
     */
    public double calculateProgress() {
        if (totalRecords == null || totalRecords == 0) {
            return 0;
        }
        if (exportedRecords == null) {
            return 0;
        }
        return (double) exportedRecords / totalRecords * 100;
    }
}