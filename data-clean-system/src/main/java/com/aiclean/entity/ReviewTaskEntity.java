package com.aiclean.entity;

import com.aiclean.entity.enums.ReviewTaskType;
import com.aiclean.entity.enums.TaskPriority;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 审核任务实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review_task")
public class ReviewTaskEntity extends BaseEntity {
    
    /**
     * 任务类型
     */
    private ReviewTaskType taskType;
    
    /**
     * 实体类型（cleaned_data, match_result等）
     */
    private String entityType;
    
    /**
     * 实体ID
     */
    private Long entityId;
    
    /**
     * 优先级
     */
    private TaskPriority priority;
    
    /**
     * 状态（pending, assigned, in_progress, completed, cancelled）
     */
    private String status;
    
    /**
     * 任务标题
     */
    private String title;
    
    /**
     * 任务描述
     */
    private String description;
    
    /**
     * 问题详情（JSON）
     */
    private String issueDetails;
    
    /**
     * 分配给
     */
    private String assignedTo;
    
    /**
     * 分配人
     */
    private String assignedBy;
    
    /**
     * 分配时间
     */
    private LocalDateTime assignedAt;
    
    /**
     * 解决方式（approved, modified, rejected, ignored）
     */
    private String resolution;
    
    /**
     * 解决说明
     */
    private String resolutionComment;
    
    /**
     * 完成人
     */
    private String completedBy;
    
    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
    
    /**
     * 截止日期
     */
    private LocalDateTime dueDate;
    
    /**
     * 预估分钟数
     */
    private Integer estimatedMinutes;
    
    /**
     * 关联数据（非数据库字段）
     */
    @TableField(exist = false)
    private CleanedDataEntity cleanedData;
    
    /**
     * 关联原始数据（非数据库字段）
     */
    @TableField(exist = false)
    private TempDataEntity tempData;
    
    /**
     * 是否已分配
     */
    public boolean isAssigned() {
        return assignedTo != null && !assignedTo.trim().isEmpty();
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }
    
    /**
     * 是否已过期
     */
    public boolean isExpired() {
        return dueDate != null && LocalDateTime.now().isAfter(dueDate);
    }
    
    /**
     * 是否需要紧急处理
     */
    public boolean isUrgent() {
        return priority == TaskPriority.HIGH || priority == TaskPriority.URGENT;
    }
    
    /**
     * 获取状态文本
     */
    public String getStatusText() {
        switch (status) {
            case "pending": return "待处理";
            case "assigned": return "已分配";
            case "in_progress": return "处理中";
            case "completed": return "已完成";
            case "cancelled": return "已取消";
            default: return status;
        }
    }
}