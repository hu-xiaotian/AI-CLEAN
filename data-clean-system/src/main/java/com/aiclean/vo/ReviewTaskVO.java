package com.aiclean.vo;

import com.aiclean.entity.ReviewTaskEntity;
import com.aiclean.entity.enums.ReviewTaskType;
import com.aiclean.entity.enums.TaskPriority;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审核任务视图对象
 */
@Data
public class ReviewTaskVO {
    /**
     * 任务ID
     */
    private Long id;

    /**
     * 任务类型
     */
    private ReviewTaskType type;

    /**
     * 任务标题
     */
    private String title;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 数据ID
     */
    private Long dataId;

    /**
     * 数据编码
     */
    private String dataCode;

    /**
     * 数据名称
     */
    private String dataName;

    /**
     * 分类ID
     */
    private Long categoryId;

    /**
     * 分类名称
     */
    private String categoryName;

    /**
     * 创建人ID
     */
    private String creatorId;

    /**
     * 创建人姓名
     */
    private String creatorName;

    /**
     * 分配人ID
     */
    private String assigneeId;

    /**
     * 分配人姓名
     */
    private String assigneeName;

    /**
     * 优先级
     */
    private TaskPriority priority;

    /**
     * 状态：pending, assigned, in_progress, completed, cancelled
     */
    private String status;

    /**
     * 审核结果
     */
    private String reviewResult;

    /**
     * 审核意见
     */
    private String reviewComment;

    /**
     * 审核数据
     */
    private Map<String, Object> reviewData;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 分配时间
     */
    private LocalDateTime assignTime;

    /**
     * 开始处理时间
     */
    private LocalDateTime startTime;

    /**
     * 完成时间
     */
    private LocalDateTime completeTime;

    /**
     * 截止时间
     */
    private LocalDateTime dueTime;

    /**
     * 预计处理时长（分钟）
     */
    private Integer estimatedDuration;

    /**
     * 实际处理时长（分钟）
     */
    private Integer actualDuration;

    /**
     * 是否逾期
     */
    private Boolean overdue;

    /**
     * 逾期天数
     */
    private Integer overdueDays;

    /**
     * 相关数据详情
     */
    private Map<String, Object> relatedData;

    /**
     * 从实体类创建视图对象
     */
    public static ReviewTaskVO fromEntity(ReviewTaskEntity entity) {
        ReviewTaskVO vo = new ReviewTaskVO();
        vo.setId(entity.getId());
        vo.setType(entity.getTaskType());
        vo.setTitle(entity.getTitle());
        vo.setDescription(entity.getDescription());
        vo.setDataId(entity.getEntityId());
        vo.setCreatorId(entity.getCreatedBy());
        vo.setAssigneeId(entity.getAssignedTo());
        vo.setPriority(entity.getPriority());
        vo.setStatus(entity.getStatus());
        vo.setReviewResult(entity.getResolution());
        vo.setReviewComment(entity.getResolutionComment());
        vo.setCreateTime(entity.getCreatedAt());
        vo.setAssignTime(entity.getAssignedAt());
        vo.setCompleteTime(entity.getCompletedAt());
        vo.setDueTime(entity.getDueDate());
        vo.setEstimatedDuration(entity.getEstimatedMinutes());
        return vo;
    }


    /**
     * 计算是否逾期
     */
    public void calculateOverdue() {
        if (dueTime == null || completeTime != null) {
            this.overdue = false;
            this.overdueDays = 0;
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(dueTime)) {
            this.overdue = true;
            this.overdueDays = (int) java.time.Duration.between(dueTime, now).toDays();
        } else {
            this.overdue = false;
            this.overdueDays = 0;
        }
    }

    /**
     * 计算实际处理时长
     */
    public void calculateActualDuration() {
        if (startTime == null || completeTime == null) {
            this.actualDuration = null;
            return;
        }

        this.actualDuration = (int) java.time.Duration.between(startTime, completeTime).toMinutes();
    }

    /**
     * 判断任务是否可领取
     */
    public boolean canClaim() {
        return "pending".equals(status) || "assigned".equals(status);
    }

    /**
     * 判断任务是否可提交
     */
    public boolean canSubmit() {
        return "in_progress".equals(status);
    }

    /**
     * 判断任务是否可取消
     */
    public boolean canCancel() {
        return !"completed".equals(status) && !"cancelled".equals(status);
    }

    /**
     * 判断任务是否已完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * 判断任务是否已取消
     */
    public boolean isCancelled() {
        return "cancelled".equals(status);
    }

    /**
     * 获取任务进度百分比
     */
    public Integer getProgressPercentage() {
        if (isCompleted()) {
            return 100;
        } else if (isCancelled()) {
            return 0;
        } else if ("in_progress".equals(status)) {
            return 50;
        } else if ("assigned".equals(status)) {
            return 25;
        } else {
            return 0;
        }
    }

    /**
     * 获取任务状态颜色
     */
    public String getStatusColor() {
        switch (status) {
            case "pending":
                return "gray";
            case "assigned":
                return "blue";
            case "in_progress":
                return "orange";
            case "completed":
                return "green";
            case "cancelled":
                return "red";
            default:
                return "gray";
        }
    }

    /**
     * 获取优先级颜色
     */
    public String getPriorityColor() {
        if (priority == null) {
            return "gray";
        }
        switch (priority) {
            case LOW:
                return "green";
            case MEDIUM:
                return "blue";
            case HIGH:
                return "orange";
            case URGENT:
                return "red";
            default:
                return "gray";
        }
    }
}