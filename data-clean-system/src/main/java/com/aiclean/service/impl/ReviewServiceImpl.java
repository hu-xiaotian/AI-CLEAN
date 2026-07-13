package com.aiclean.service.impl;

import com.aiclean.entity.ReviewTaskEntity;
import com.aiclean.entity.enums.ReviewTaskType;
import com.aiclean.entity.enums.TaskPriority;
import com.aiclean.dto.StatusCount;
import com.aiclean.dto.AssigneeStatusCount;
import com.aiclean.dto.TaskTypeCount;
import com.aiclean.mapper.ReviewTaskMapper;
import com.aiclean.service.ReviewService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 审核服务实现类
 */
@Service
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private ReviewTaskMapper reviewTaskMapper;

    @Override
    @Transactional
    public ReviewTaskEntity createReviewTask(ReviewTaskEntity task) {
        log.info("创建审核任务: {}", task);
        
        // 验证必填字段
        if (task.getTaskType() == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (StringUtils.isBlank(task.getTitle())) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        if (StringUtils.isBlank(task.getEntityType())) {
            throw new IllegalArgumentException("实体类型不能为空");
        }
        if (task.getEntityId() == null) {
            throw new IllegalArgumentException("实体ID不能为空");
        }
        
        // 设置默认值
        if (task.getPriority() == null) {
            task.setPriority(TaskPriority.MEDIUM);
        }
        if (task.getStatus() == null) {
            task.setStatus("pending");
        }
        if (task.getEstimatedMinutes() == null) {
            task.setEstimatedMinutes(30); // 默认30分钟
        }
        
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        // 插入数据库
        reviewTaskMapper.insert(task);
        
        log.info("审核任务创建成功, ID: {}", task.getId());
        return task;
    }

    @Override
    @Transactional
    public int batchCreateReviewTasks(List<ReviewTaskEntity> tasks) {
        log.info("批量创建审核任务, 数量: {}", tasks.size());
        
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        
        int createdCount = 0;
        for (ReviewTaskEntity task : tasks) {
            try {
                createReviewTask(task);
                createdCount++;
            } catch (Exception e) {
                log.error("创建审核任务失败: {}", task, e);
                throw new RuntimeException("批量创建审核任务时出错", e);
            }
        }
        
        log.info("批量创建完成, 成功创建 {} 个任务", createdCount);
        return createdCount;
    }

    @Override
    @Transactional
    public ReviewTaskEntity updateReviewTask(ReviewTaskEntity task) {
        log.info("更新审核任务: {}", task);
        
        if (task.getId() == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        
        // 获取现有任务
        ReviewTaskEntity existing = reviewTaskMapper.selectById(task.getId());
        if (existing == null) {
            throw new IllegalArgumentException("审核任务不存在: " + task.getId());
        }
        
        // 更新字段
        boolean updated = false;
        
        if (task.getTaskType() != null) {
            existing.setTaskType(task.getTaskType());
            updated = true;
        }
        if (StringUtils.isNotBlank(task.getTitle())) {
            existing.setTitle(task.getTitle());
            updated = true;
        }
        if (StringUtils.isNotBlank(task.getDescription())) {
            existing.setDescription(task.getDescription());
            updated = true;
        }
        if (task.getPriority() != null) {
            existing.setPriority(task.getPriority());
            updated = true;
        }
        if (StringUtils.isNotBlank(task.getStatus())) {
            // 状态变更校验
            validateStatusTransition(existing.getStatus(), task.getStatus());
            existing.setStatus(task.getStatus());
            updated = true;
        }
        if (task.getIssueDetails() != null) {
            existing.setIssueDetails(task.getIssueDetails());
            updated = true;
        }
        if (task.getEstimatedMinutes() != null) {
            existing.setEstimatedMinutes(task.getEstimatedMinutes());
            updated = true;
        }
        if (task.getDueDate() != null) {
            existing.setDueDate(task.getDueDate());
            updated = true;
        }
        
        if (updated) {
            existing.setUpdatedAt(LocalDateTime.now());
            reviewTaskMapper.updateById(existing);
        }
        
        log.info("审核任务更新成功, ID: {}", existing.getId());
        return existing;
    }

    @Override
    @Transactional
    public void deleteReviewTask(Long taskId) {
        log.info("删除审核任务: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 只能删除未开始或已完成的任务
        if (!"pending".equals(task.getStatus()) && !"completed".equals(task.getStatus()) && !"cancelled".equals(task.getStatus())) {
            throw new IllegalStateException("只能删除未开始、已完成或已取消的任务");
        }
        
        // 物理删除
        reviewTaskMapper.deleteById(taskId);
        
        log.info("审核任务删除成功, ID: {}", taskId);
    }

    @Override
    public ReviewTaskEntity getReviewTaskById(Long taskId) {
        return reviewTaskMapper.selectById(taskId);
    }

    @Override
    public List<ReviewTaskEntity> getMyPendingTasks(String userId) {
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ReviewTaskEntity::getAssignedTo, userId)
                   .in(ReviewTaskEntity::getStatus, Arrays.asList("assigned", "in_progress"))
                   .orderByDesc(ReviewTaskEntity::getPriority)
                   .orderByAsc(ReviewTaskEntity::getDueDate)
                   .orderByDesc(ReviewTaskEntity::getCreatedAt);
        
        return reviewTaskMapper.selectList(queryWrapper);
    }

    @Override
    public List<ReviewTaskEntity> getAssignedTasks(String userId) {
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        return reviewTaskMapper.selectByAssignedTo(userId);
    }

    @Override
    public List<ReviewTaskEntity> getMyCompletedTasks(String userId) {
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ReviewTaskEntity::getAssignedTo, userId)
                   .eq(ReviewTaskEntity::getStatus, "completed")
                   .orderByDesc(ReviewTaskEntity::getCompletedAt);
        
        return reviewTaskMapper.selectList(queryWrapper);
    }

    @Override
    public List<ReviewTaskEntity> getUnassignedTasks() {
        return reviewTaskMapper.selectUnassignedTasks();
    }

    @Override
    public List<ReviewTaskEntity> getAllTasks(int page, int size) {
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByDesc(ReviewTaskEntity::getPriority)
                   .orderByDesc(ReviewTaskEntity::getCreatedAt);
        
        // 分页查询
        queryWrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);
        
        return reviewTaskMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional
    public ReviewTaskEntity assignTask(Long taskId, String assignee, String assigner) {
        log.info("分配任务, ID: {}, 分配给: {}, 分配人: {}", taskId, assignee, assigner);
        
        if (StringUtils.isBlank(assignee)) {
            throw new IllegalArgumentException("审核人不能为空");
        }
        if (StringUtils.isBlank(assigner)) {
            throw new IllegalArgumentException("分配人不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 检查任务状态
        if (!"pending".equals(task.getStatus())) {
            throw new IllegalStateException("只能分配待处理的任务");
        }
        
        // 分配任务
        int rows = reviewTaskMapper.assignTask(taskId, assignee, assigner);
        if (rows == 0) {
            throw new RuntimeException("任务分配失败");
        }
        
        // 获取更新后的任务
        task = reviewTaskMapper.selectById(taskId);
        
        log.info("任务分配成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public int batchAssignTasks(List<Long> taskIds, String assignee, String assigner) {
        log.info("批量分配任务, IDs: {}, 分配给: {}, 分配人: {}", taskIds, assignee, assigner);
        
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        if (StringUtils.isBlank(assignee)) {
            throw new IllegalArgumentException("审核人不能为空");
        }
        if (StringUtils.isBlank(assigner)) {
            throw new IllegalArgumentException("分配人不能为空");
        }
        
        // 批量分配
        int rows = reviewTaskMapper.assignTasksBatch(taskIds, assignee, assigner);
        
        log.info("批量分配完成, 成功分配 {} 个任务", rows);
        return rows;
    }

    @Override
    @Transactional
    public ReviewTaskEntity reassignTask(Long taskId, String newAssignee, String reassigner) {
        log.info("重新分配任务, ID: {}, 新审核人: {}, 重新分配人: {}", taskId, newAssignee, reassigner);
        
        if (StringUtils.isBlank(newAssignee)) {
            throw new IllegalArgumentException("新审核人不能为空");
        }
        if (StringUtils.isBlank(reassigner)) {
            throw new IllegalArgumentException("重新分配人不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 检查任务状态
        if (!"assigned".equals(task.getStatus()) && !"in_progress".equals(task.getStatus())) {
            throw new IllegalStateException("只能重新分配已分配或处理中的任务");
        }
        
        // 重新分配任务
        task.setAssignedTo(newAssignee);
        task.setAssignedBy(reassigner);
        task.setAssignedAt(LocalDateTime.now());
        task.setStatus("assigned");
        task.setUpdatedAt(LocalDateTime.now());
        
        reviewTaskMapper.updateById(task);
        
        log.info("任务重新分配成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public ReviewTaskEntity startTask(Long taskId, String userId) {
        log.info("开始处理任务, ID: {}, 用户: {}", taskId, userId);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 检查任务是否分配给当前用户
        if (!userId.equals(task.getAssignedTo())) {
            throw new IllegalStateException("只能开始处理分配给自己的任务");
        }
        
        // 检查任务状态
        if (!"assigned".equals(task.getStatus())) {
            throw new IllegalStateException("只能开始处理已分配的任务");
        }
        
        // 开始处理任务
        int rows = reviewTaskMapper.startTask(taskId, userId);
        if (rows == 0) {
            throw new RuntimeException("开始处理任务失败");
        }
        
        // 获取更新后的任务
        task = reviewTaskMapper.selectById(taskId);
        
        log.info("任务开始处理成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public ReviewTaskEntity completeTask(Long taskId, String userId, String resolution, String comment) {
        log.info("完成任务, ID: {}, 用户: {}, 解决方式: {}", taskId, userId, resolution);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(resolution)) {
            throw new IllegalArgumentException("解决方式不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 检查任务是否分配给当前用户
        if (!userId.equals(task.getAssignedTo())) {
            throw new IllegalStateException("只能完成分配给自己的任务");
        }
        
        // 检查任务状态
        if (!"in_progress".equals(task.getStatus())) {
            throw new IllegalStateException("只能完成处理中的任务");
        }
        
        // 完成任务
        int rows = reviewTaskMapper.completeTask(taskId, resolution, comment, userId);
        if (rows == 0) {
            throw new RuntimeException("完成任务失败");
        }
        
        // 获取更新后的任务
        task = reviewTaskMapper.selectById(taskId);
        
        log.info("任务完成成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public int batchCompleteTasks(List<Long> taskIds, String userId, String resolution, String comment) {
        log.info("批量完成任务, IDs: {}, 用户: {}, 解决方式: {}", taskIds, userId, resolution);
        
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(resolution)) {
            throw new IllegalArgumentException("解决方式不能为空");
        }
        
        int completedCount = 0;
        for (Long taskId : taskIds) {
            try {
                completeTask(taskId, userId, resolution, comment);
                completedCount++;
            } catch (Exception e) {
                log.error("完成任务失败, ID: {}", taskId, e);
                throw new RuntimeException("批量完成任务时出错", e);
            }
        }
        
        log.info("批量完成完成, 成功完成 {} 个任务", completedCount);
        return completedCount;
    }

    @Override
    @Transactional
    public ReviewTaskEntity cancelTask(Long taskId, String userId, String reason) {
        log.info("取消任务, ID: {}, 用户: {}, 原因: {}", taskId, userId, reason);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 只能取消待处理或已分配的任务
        if (!"pending".equals(task.getStatus()) && !"assigned".equals(task.getStatus())) {
            throw new IllegalStateException("只能取消待处理或已分配的任务");
        }
        
        // 取消任务
        int rows = reviewTaskMapper.cancelTask(taskId);
        if (rows == 0) {
            throw new RuntimeException("取消任务失败");
        }
        
        // 添加取消原因到描述
        if (StringUtils.isNotBlank(reason)) {
            String newDescription = task.getDescription() != null ? 
                    task.getDescription() + "\n\n取消原因: " + reason : 
                    "取消原因: " + reason;
            task.setDescription(newDescription);
            task.setUpdatedAt(LocalDateTime.now());
            reviewTaskMapper.updateById(task);
        }
        
        // 获取更新后的任务
        task = reviewTaskMapper.selectById(taskId);
        
        log.info("任务取消成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public ReviewTaskEntity reopenTask(Long taskId, String userId, String reason) {
        log.info("重新打开任务, ID: {}, 用户: {}, 原因: {}", taskId, userId, reason);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 只能重新打开已完成或已取消的任务
        if (!"completed".equals(task.getStatus()) && !"cancelled".equals(task.getStatus())) {
            throw new IllegalStateException("只能重新打开已完成或已取消的任务");
        }
        
        // 重新打开任务
        task.setStatus("pending");
        if (StringUtils.isNotBlank(reason)) {
            String newDescription = task.getDescription() != null ? 
                    task.getDescription() + "\n\n重新打开原因: " + reason : 
                    "重新打开原因: " + reason;
            task.setDescription(newDescription);
        }
        task.setAssignedTo(null);
        task.setAssignedBy(null);
        task.setAssignedAt(null);
        task.setCompletedBy(null);
        task.setCompletedAt(null);
        task.setResolution(null);
        task.setResolutionComment(null);
        task.setUpdatedAt(LocalDateTime.now());
        
        reviewTaskMapper.updateById(task);
        
        log.info("任务重新打开成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public ReviewTaskEntity updateTaskPriority(Long taskId, TaskPriority priority) {
        log.info("更新任务优先级, ID: {}, 优先级: {}", taskId, priority);
        
        if (priority == null) {
            throw new IllegalArgumentException("优先级不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        task.setPriority(priority);
        task.setUpdatedAt(LocalDateTime.now());
        
        reviewTaskMapper.updateById(task);
        
        log.info("任务优先级更新成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public ReviewTaskEntity updateTaskDueDate(Long taskId, LocalDateTime dueDate) {
        log.info("更新任务截止日期, ID: {}, 截止日期: {}", taskId, dueDate);
        
        if (dueDate == null) {
            throw new IllegalArgumentException("截止日期不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        task.setDueDate(dueDate);
        task.setUpdatedAt(LocalDateTime.now());
        
        reviewTaskMapper.updateById(task);
        
        log.info("任务截止日期更新成功, ID: {}", taskId);
        return task;
    }

    @Override
    @Transactional
    public Long addTaskComment(Long taskId, String userId, String comment) {
        log.info("添加任务评论, ID: {}, 用户: {}, 评论: {}", taskId, userId, comment);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(comment)) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // 将评论添加到任务描述
        String newDescription = task.getDescription() != null ? 
                task.getDescription() + "\n\n评论 [" + LocalDateTime.now() + "] [" + userId + "]: " + comment :
                "评论 [" + LocalDateTime.now() + "] [" + userId + "]: " + comment;
        task.setDescription(newDescription);
        task.setUpdatedAt(LocalDateTime.now());
        
        reviewTaskMapper.updateById(task);
        
        // TODO: 在实际系统中，这里应该创建一个独立的评论记录表
        // 暂时返回一个占位符ID
        Long commentId = System.currentTimeMillis();
        
        log.info("任务评论添加成功, 评论ID: {}", commentId);
        return commentId;
    }

    @Override
    public List<Map<String, Object>> getTaskHistory(Long taskId) {
        log.info("获取任务历史, ID: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        List<Map<String, Object>> history = new ArrayList<>();
        
        // 创建历史
        Map<String, Object> createRecord = new HashMap<>();
        createRecord.put("type", "create");
        createRecord.put("user", "system");
        createRecord.put("time", task.getCreatedAt());
        createRecord.put("details", "任务创建");
        history.add(createRecord);
        
        // 分配历史
        if (task.getAssignedAt() != null) {
            Map<String, Object> assignRecord = new HashMap<>();
            assignRecord.put("type", "assign");
            assignRecord.put("user", task.getAssignedBy());
            assignRecord.put("time", task.getAssignedAt());
            assignRecord.put("details", "分配给 " + task.getAssignedTo());
            history.add(assignRecord);
        }
        
        // 状态变更历史
        Map<String, Object> statusRecord = new HashMap<>();
        statusRecord.put("type", "status_change");
        statusRecord.put("user", "system");
        statusRecord.put("time", task.getUpdatedAt());
        statusRecord.put("details", "状态变更为 " + task.getStatus());
        history.add(statusRecord);
        
        // 完成历史
        if (task.getCompletedAt() != null) {
            Map<String, Object> completeRecord = new HashMap<>();
            completeRecord.put("type", "complete");
            completeRecord.put("user", task.getCompletedBy());
            completeRecord.put("time", task.getCompletedAt());
            completeRecord.put("details", "任务完成，解决方式: " + task.getResolution());
            history.add(completeRecord);
        }
        
        // 按时间排序
        history.sort(Comparator.comparing(record -> (LocalDateTime) record.get("time")));
        
        log.info("任务历史获取完成, 记录数: {}", history.size());
        return history;
    }

    @Override
    public List<ReviewTaskEntity> searchTasks(String keyword, String status, TaskPriority priority, 
                                             String assignee, ReviewTaskType taskType, 
                                             LocalDateTime startDate, LocalDateTime endDate) {
        log.info("搜索任务, 关键字: {}, 状态: {}, 优先级: {}, 审核人: {}, 任务类型: {}, 开始日期: {}, 结束日期: {}", 
                keyword, status, priority, assignee, taskType, startDate, endDate);
        
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        
        if (StringUtils.isNotBlank(keyword)) {
            queryWrapper.and(qw -> qw
                    .like(ReviewTaskEntity::getTitle, keyword)
                    .or()
                    .like(ReviewTaskEntity::getDescription, keyword));
        }
        
        if (StringUtils.isNotBlank(status)) {
            queryWrapper.eq(ReviewTaskEntity::getStatus, status);
        }
        
        if (priority != null) {
            queryWrapper.eq(ReviewTaskEntity::getPriority, priority);
        }
        
        if (StringUtils.isNotBlank(assignee)) {
            queryWrapper.eq(ReviewTaskEntity::getAssignedTo, assignee);
        }
        
        if (taskType != null) {
            queryWrapper.eq(ReviewTaskEntity::getTaskType, taskType);
        }
        
        if (startDate != null) {
            queryWrapper.ge(ReviewTaskEntity::getCreatedAt, startDate);
        }
        
        if (endDate != null) {
            queryWrapper.le(ReviewTaskEntity::getCreatedAt, endDate);
        }
        
        queryWrapper.orderByDesc(ReviewTaskEntity::getPriority)
                   .orderByDesc(ReviewTaskEntity::getCreatedAt);
        
        List<ReviewTaskEntity> tasks = reviewTaskMapper.selectList(queryWrapper);
        
        log.info("任务搜索完成, 结果数: {}", tasks.size());
        return tasks;
    }

    @Override
    public List<ReviewTaskEntity> getTasksByEntity(String entityType, Long entityId) {
        if (StringUtils.isBlank(entityType)) {
            throw new IllegalArgumentException("实体类型不能为空");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("实体ID不能为空");
        }
        
        return reviewTaskMapper.selectByEntity(entityType, entityId);
    }

    @Override
    public Map<String, Object> getTaskStatistics(String userId) {
        log.info("获取任务统计信息, 用户: {}", userId);
        
        Map<String, Object> stats = new HashMap<>();
        
        // 获取所有任务统计
        List<StatusCount> statusCounts = reviewTaskMapper.countByStatus();
        List<AssigneeStatusCount> assigneeCounts = reviewTaskMapper.countByAssignee();
        List<TaskTypeCount> taskTypeCounts = reviewTaskMapper.countByTaskType();
        
        // 总体统计
        stats.put("totalTasks", getTotalTaskCount(statusCounts));
        stats.put("pendingTasks", getCountByStatus(statusCounts, "pending"));
        stats.put("assignedTasks", getCountByStatus(statusCounts, "assigned"));
        stats.put("inProgressTasks", getCountByStatus(statusCounts, "in_progress"));
        stats.put("completedTasks", getCountByStatus(statusCounts, "completed"));
        stats.put("cancelledTasks", getCountByStatus(statusCounts, "cancelled"));
        
        // 按类型统计
        Map<String, Integer> typeStats = new HashMap<>();
        for (TaskTypeCount typeCount : taskTypeCounts) {
            typeStats.put(typeCount.getTaskType(), typeCount.getCount());
        }
        stats.put("taskTypeStats", typeStats);
        
        // 用户特定统计
        if (StringUtils.isNotBlank(userId)) {
            Map<String, Integer> userStats = getUserTaskStats(assigneeCounts, userId);
            stats.put("userStats", userStats);
            
            // 获取我的任务
            List<ReviewTaskEntity> myTasks = getMyPendingTasks(userId);
            stats.put("myPendingTasks", myTasks.size());
            
            List<ReviewTaskEntity> myCompletedTasks = getMyCompletedTasks(userId);
            stats.put("myCompletedTasks", myCompletedTasks.size());
        }
        
        // 获取即将到期的任务
        List<ReviewTaskEntity> expiringTasks = getUpcomingDueTasks(24);
        stats.put("expiringTasks", expiringTasks.size());
        
        // 获取超时任务
        List<ReviewTaskEntity> overdueTasks = getOverdueTasks();
        stats.put("overdueTasks", overdueTasks.size());
        
        log.info("任务统计信息获取完成");
        return stats;
    }

    @Override
    public Map<String, Object> getTeamWorkload() {
        log.info("获取团队工作负载");
        
        List<AssigneeStatusCount> assigneeCounts = reviewTaskMapper.countByAssignee();
        
        Map<String, Object> workload = new HashMap<>();
        Map<String, Map<String, Integer>> userWorkload = new HashMap<>();
        
        // 按用户统计
        for (AssigneeStatusCount count : assigneeCounts) {
            String assignee = count.getAssigneeId();
            if (!userWorkload.containsKey(assignee)) {
                userWorkload.put(assignee, new HashMap<>());
            }
            userWorkload.get(assignee).put(count.getStatus(), count.getCount());
        }
        
        // 计算每个用户的总任务数
        Map<String, Integer> totalTasksByUser = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : userWorkload.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            totalTasksByUser.put(entry.getKey(), total);
        }
        
        // 计算平均负载
        double avgLoad = totalTasksByUser.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
        
        workload.put("userWorkload", userWorkload);
        workload.put("totalTasksByUser", totalTasksByUser);
        workload.put("averageLoad", avgLoad);
        
        // 识别过载用户（任务数超过平均值的1.5倍）
        List<String> overloadedUsers = totalTasksByUser.entrySet().stream()
                .filter(entry -> entry.getValue() > avgLoad * 1.5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        workload.put("overloadedUsers", overloadedUsers);
        
        // 识别空闲用户（任务数低于平均值的0.5倍）
        List<String> idleUsers = totalTasksByUser.entrySet().stream()
                .filter(entry -> entry.getValue() < avgLoad * 0.5 && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        workload.put("idleUsers", idleUsers);
        
        log.info("团队工作负载获取完成");
        return workload;
    }

    @Override
    public List<ReviewTaskEntity> getOverdueTasks() {
        LocalDateTime now = LocalDateTime.now();
        
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ReviewTaskEntity::getStatus, Arrays.asList("assigned", "in_progress"))
                   .lt(ReviewTaskEntity::getDueDate, now)
                   .orderByAsc(ReviewTaskEntity::getDueDate);
        
        return reviewTaskMapper.selectList(queryWrapper);
    }

    @Override
    public List<ReviewTaskEntity> getUpcomingDueTasks(int hours) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusHours(hours);
        
        LambdaQueryWrapper<ReviewTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ReviewTaskEntity::getStatus, Arrays.asList("assigned", "in_progress"))
                   .between(ReviewTaskEntity::getDueDate, now, deadline)
                   .orderByAsc(ReviewTaskEntity::getDueDate);
        
        return reviewTaskMapper.selectList(queryWrapper);
    }

    @Override
    public List<ReviewTaskEntity> getHighPriorityTasks() {
        return reviewTaskMapper.selectByPriority("HIGH");
    }

    @Override
    @Transactional
    public ReviewTaskEntity autoAssignTask(Long taskId) {
        log.info("自动分配任务, ID: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        if (!"pending".equals(task.getStatus())) {
            throw new IllegalStateException("只能自动分配待处理的任务");
        }
        
        // TODO: 实现基于负载均衡的自动分配算法
        // 暂时使用简单的分配策略：分配给任务最少的用户
        String assignee = findBestAssignee();
        
        if (StringUtils.isBlank(assignee)) {
            throw new RuntimeException("没有可用的审核人");
        }
        
        // 分配任务
        return assignTask(taskId, assignee, "system");
    }

    @Override
    @Transactional
    public int batchAutoAssignTasks(List<Long> taskIds) {
        log.info("批量自动分配任务, IDs: {}", taskIds);
        
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        
        int assignedCount = 0;
        for (Long taskId : taskIds) {
            try {
                autoAssignTask(taskId);
                assignedCount++;
            } catch (Exception e) {
                log.error("自动分配任务失败, ID: {}", taskId, e);
                throw new RuntimeException("批量自动分配任务时出错", e);
            }
        }
        
        log.info("批量自动分配完成, 成功分配 {} 个任务", assignedCount);
        return assignedCount;
    }

    @Override
    @Transactional
    public ReviewTaskEntity createQualityCheckTask(String entityType, Long entityId, 
                                                  List<Map<String, Object>> issues, TaskPriority priority) {
        log.info("创建数据质量检查任务, 实体类型: {}, 实体ID: {}, 问题数: {}, 优先级: {}", 
                entityType, entityId, issues != null ? issues.size() : 0, priority);
        
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setTaskType(ReviewTaskType.DATA_VALIDATION);
        task.setEntityType(entityType);
        task.setEntityId(entityId);
        task.setPriority(priority != null ? priority : TaskPriority.MEDIUM);
        
        // 构建任务标题和描述
        String title = "数据质量检查 - " + entityType + " #" + entityId;
        if (issues != null && !issues.isEmpty()) {
            title += " (" + issues.size() + " 个问题)";
        }
        task.setTitle(title);
        
        // 构建任务描述
        StringBuilder description = new StringBuilder();
        description.append("数据质量检查任务\n\n");
        description.append("实体类型: ").append(entityType).append("\n");
        description.append("实体ID: ").append(entityId).append("\n\n");
        
        if (issues != null && !issues.isEmpty()) {
            description.append("发现问题:\n");
            for (int i = 0; i < issues.size(); i++) {
                Map<String, Object> issue = issues.get(i);
                description.append(i + 1).append(". ").append(issue.get("description")).append("\n");
                if (issue.containsKey("severity")) {
                    description.append("   严重程度: ").append(issue.get("severity")).append("\n");
                }
                if (issue.containsKey("field")) {
                    description.append("   字段: ").append(issue.get("field")).append("\n");
                }
                description.append("\n");
            }
        }
        
        task.setDescription(description.toString());
        
        // 如果有问题详情，保存为JSON
        if (issues != null && !issues.isEmpty()) {
            // 这里应该使用JSON库将issues转换为JSON字符串
            task.setIssueDetails("{\"issues\": " + issues.size() + "}");
        }
        
        return createReviewTask(task);
    }

    @Override
    @Transactional
    public ReviewTaskEntity createFieldMappingAuditTask(Long mappingId, Double confidence, 
                                                       List<Map<String, Object>> suggestions) {
        log.info("创建字段映射审核任务, 映射ID: {}, 置信度: {}, 建议数: {}", 
                mappingId, confidence, suggestions != null ? suggestions.size() : 0);
        
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setTaskType(ReviewTaskType.FIELD_MAPPING);
        task.setEntityType("field_mapping");
        task.setEntityId(mappingId);
        
        // 根据置信度设置优先级
        if (confidence != null) {
            if (confidence < 0.7) {
                task.setPriority(TaskPriority.HIGH);
            } else if (confidence < 0.9) {
                task.setPriority(TaskPriority.MEDIUM);
            } else {
                task.setPriority(TaskPriority.LOW);
            }
        } else {
            task.setPriority(TaskPriority.MEDIUM);
        }
        
        String title = "字段映射审核 - 映射 #" + mappingId;
        if (confidence != null) {
            title += " (" + String.format("%.1f", confidence * 100) + "% 置信度)";
        }
        task.setTitle(title);
        
        // 构建任务描述
        StringBuilder description = new StringBuilder();
        description.append("字段映射审核任务\n\n");
        description.append("映射ID: ").append(mappingId).append("\n");
        if (confidence != null) {
            description.append("系统匹配置信度: ").append(String.format("%.1f", confidence * 100)).append("%\n");
        }
        
        if (suggestions != null && !suggestions.isEmpty()) {
            description.append("\n系统建议:\n");
            for (int i = 0; i < suggestions.size(); i++) {
                Map<String, Object> suggestion = suggestions.get(i);
                description.append(i + 1).append(". ").append(suggestion.get("suggestion")).append("\n");
                if (suggestion.containsKey("confidence")) {
                    description.append("   置信度: ").append(suggestion.get("confidence")).append("\n");
                }
                description.append("\n");
            }
        }
        
        task.setDescription(description.toString());
        
        // 保存建议为JSON
        if (suggestions != null && !suggestions.isEmpty()) {
            task.setIssueDetails("{\"suggestions\": " + suggestions.size() + "}");
        }
        
        return createReviewTask(task);
    }

    @Override
    @Transactional
    public ReviewTaskEntity createCategoryAuditTask(Long dataId, Long categoryId, Double confidence,
                                                   List<Long> alternativeCategories) {
        log.info("创建分类审核任务, 数据ID: {}, 分类ID: {}, 置信度: {}, 备选分类数: {}", 
                dataId, categoryId, confidence, alternativeCategories != null ? alternativeCategories.size() : 0);
        
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setTaskType(ReviewTaskType.CATEGORY_MATCH);
        task.setEntityType("cleaned_data");
        task.setEntityId(dataId);
        
        // 根据置信度设置优先级
        if (confidence != null) {
            if (confidence < 0.7) {
                task.setPriority(TaskPriority.HIGH);
            } else if (confidence < 0.9) {
                task.setPriority(TaskPriority.MEDIUM);
            } else {
                task.setPriority(TaskPriority.LOW);
            }
        } else {
            task.setPriority(TaskPriority.MEDIUM);
        }
        
        String title = "分类审核 - 数据 #" + dataId;
        if (confidence != null) {
            title += " (" + String.format("%.1f", confidence * 100) + "% 置信度)";
        }
        task.setTitle(title);
        
        // 构建任务描述
        StringBuilder description = new StringBuilder();
        description.append("分类审核任务\n\n");
        description.append("数据ID: ").append(dataId).append("\n");
        description.append("系统分配分类ID: ").append(categoryId).append("\n");
        if (confidence != null) {
            description.append("分类置信度: ").append(String.format("%.1f", confidence * 100)).append("%\n");
        }
        
        if (alternativeCategories != null && !alternativeCategories.isEmpty()) {
            description.append("\n备选分类:\n");
            for (int i = 0; i < alternativeCategories.size(); i++) {
                description.append(i + 1).append(". 分类ID: ").append(alternativeCategories.get(i)).append("\n");
            }
        }
        
        task.setDescription(description.toString());
        
        // 保存备选分类为JSON
        if (alternativeCategories != null && !alternativeCategories.isEmpty()) {
            task.setIssueDetails("{\"alternative_categories\": " + alternativeCategories.size() + "}");
        }
        
        return createReviewTask(task);
    }

    @Override
    public String exportTaskReport(String format, Map<String, Object> filters) {
        log.info("导出任务报告, 格式: {}, 过滤条件: {}", format, filters);
        
        // TODO: 实现报告导出逻辑
        // 这里应该根据过滤条件查询任务，然后根据格式生成报告文件
        
        String exportPath = "/tmp/task_report_" + System.currentTimeMillis() + "." + format;
        log.info("报告导出完成, 文件路径: {}", exportPath);
        return exportPath;
    }

    @Override
    public List<Map<String, Object>> getTaskReminders(String userId) {
        log.info("获取任务提醒, 用户: {}", userId);
        
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        List<Map<String, Object>> reminders = new ArrayList<>();
        
        // 获取我的任务
        List<ReviewTaskEntity> myTasks = getMyPendingTasks(userId);
        for (ReviewTaskEntity task : myTasks) {
            // 检查即将到期的任务
            if (task.getDueDate() != null && !task.isExpired()) {
                LocalDateTime dueDate = task.getDueDate();
                LocalDateTime now = LocalDateTime.now();
                
                if (dueDate.isBefore(now.plusHours(24))) {
                    Map<String, Object> reminder = new HashMap<>();
                    reminder.put("taskId", task.getId());
                    reminder.put("title", task.getTitle());
                    reminder.put("type", "due_soon");
                    reminder.put("dueDate", task.getDueDate());
                    reminder.put("hoursRemaining", (int) java.time.Duration.between(now, dueDate).toHours());
                    reminders.add(reminder);
                }
            }
            
            // 检查高优先级任务
            if (task.isUrgent()) {
                Map<String, Object> reminder = new HashMap<>();
                reminder.put("taskId", task.getId());
                reminder.put("title", task.getTitle());
                reminder.put("type", "high_priority");
                reminder.put("priority", task.getPriority());
                reminder.put("status", task.getStatus());
                reminders.add(reminder);
            }
            
            // 检查长时间未处理的任务
            if ("assigned".equals(task.getStatus())) {
                LocalDateTime assignedAt = task.getAssignedAt();
                LocalDateTime now = LocalDateTime.now();
                
                if (assignedAt != null && assignedAt.isBefore(now.minusDays(2))) {
                    Map<String, Object> reminder = new HashMap<>();
                    reminder.put("taskId", task.getId());
                    reminder.put("title", task.getTitle());
                    reminder.put("type", "long_pending");
                    reminder.put("assignedAt", task.getAssignedAt());
                    reminder.put("daysPending", (int) java.time.Duration.between(assignedAt, now).toDays());
                    reminders.add(reminder);
                }
            }
        }
        
        log.info("任务提醒获取完成, 提醒数: {}", reminders.size());
        return reminders;
    }

    @Override
    @Transactional
    public void markTaskAsRead(Long taskId, String userId) {
        log.info("标记任务为已读, ID: {}, 用户: {}", taskId, userId);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        // TODO: 在实际系统中，这里应该更新任务阅读记录表
        // 暂时只记录日志
        
        log.info("任务已读标记完成, ID: {}, 用户: {}", taskId, userId);
    }

    @Override
    @Transactional
    public void setTaskWatching(Long taskId, String userId, boolean watching) {
        log.info("设置任务关注, ID: {}, 用户: {}, 关注: {}", taskId, userId, watching);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        // TODO: 在实际系统中，这里应该更新任务关注记录表
        // 暂时只记录日志
        
        log.info("任务关注设置完成, ID: {}, 用户: {}, 关注: {}", taskId, userId, watching);
    }

    @Override
    public List<ReviewTaskEntity> getWatchedTasks(String userId) {
        log.info("获取关注的任务, 用户: {}", userId);
        
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        // TODO: 在实际系统中，这里应该从任务关注记录表查询
        // 暂时返回空列表
        
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> getTaskDiscussions(Long taskId) {
        log.info("获取任务讨论, ID: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该从讨论记录表查询
        // 暂时返回从任务描述中提取的评论
        
        List<Map<String, Object>> discussions = new ArrayList<>();
        
        if (StringUtils.isNotBlank(task.getDescription())) {
            // 简单地从描述中提取评论
            String[] lines = task.getDescription().split("\n");
            for (String line : lines) {
                if (line.contains("[评论]")) {
                    Map<String, Object> discussion = new HashMap<>();
                    discussion.put("type", "comment");
                    discussion.put("content", line);
                    discussions.add(discussion);
                }
            }
        }
        
        log.info("任务讨论获取完成, 讨论数: {}", discussions.size());
        return discussions;
    }

    @Override
    @Transactional
    public Long addTaskAttachment(Long taskId, String userId, String fileName, byte[] fileData, String fileType) {
        log.info("添加任务附件, ID: {}, 用户: {}, 文件名: {}, 文件类型: {}", taskId, userId, fileName, fileType);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (fileData == null || fileData.length == 0) {
            throw new IllegalArgumentException("文件数据不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该保存文件到文件系统或对象存储，并在数据库中添加附件记录
        // 暂时只返回一个占位符ID
        
        Long attachmentId = System.currentTimeMillis();
        
        log.info("任务附件添加成功, 附件ID: {}", attachmentId);
        return attachmentId;
    }

    @Override
    public List<Map<String, Object>> getTaskAttachments(Long taskId) {
        log.info("获取任务附件, ID: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该从附件记录表查询
        // 暂时返回空列表
        
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public void deleteTaskAttachment(Long attachmentId, String userId) {
        log.info("删除任务附件, 附件ID: {}, 用户: {}", attachmentId, userId);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        // TODO: 在实际系统中，这里应该删除附件文件并更新附件记录表
        // 暂时只记录日志
        
        log.info("任务附件删除完成, 附件ID: {}, 用户: {}", attachmentId, userId);
    }

    @Override
    public List<Map<String, Object>> getTaskTemplates() {
        log.info("获取任务模板");
        
        // TODO: 在实际系统中，这里应该从模板表查询
        // 暂时返回几个示例模板
        
        List<Map<String, Object>> templates = new ArrayList<>();
        
        // 数据质量检查模板
        Map<String, Object> qualityCheckTemplate = new HashMap<>();
        qualityCheckTemplate.put("id", "quality_check");
        qualityCheckTemplate.put("name", "数据质量检查");
        qualityCheckTemplate.put("taskType", ReviewTaskType.DATA_VALIDATION);
        qualityCheckTemplate.put("priority", TaskPriority.MEDIUM);
        qualityCheckTemplate.put("estimatedMinutes", 60);
        qualityCheckTemplate.put("description", "标准数据质量检查任务模板");
        templates.add(qualityCheckTemplate);
        
        // 字段映射审核模板
        Map<String, Object> mappingAuditTemplate = new HashMap<>();
        mappingAuditTemplate.put("id", "field_mapping_audit");
        mappingAuditTemplate.put("name", "字段映射审核");
        mappingAuditTemplate.put("taskType", ReviewTaskType.FIELD_MAPPING);
        mappingAuditTemplate.put("priority", TaskPriority.HIGH);
        mappingAuditTemplate.put("estimatedMinutes", 30);
        mappingAuditTemplate.put("description", "字段映射关系审核模板");
        templates.add(mappingAuditTemplate);
        
        // 分类审核模板
        Map<String, Object> categoryAuditTemplate = new HashMap<>();
        categoryAuditTemplate.put("id", "category_audit");
        categoryAuditTemplate.put("name", "分类审核");
        categoryAuditTemplate.put("taskType", ReviewTaskType.CATEGORY_MATCH);
        categoryAuditTemplate.put("priority", TaskPriority.MEDIUM);
        categoryAuditTemplate.put("estimatedMinutes", 20);
        categoryAuditTemplate.put("description", "分类归属审核模板");
        templates.add(categoryAuditTemplate);
        
        log.info("任务模板获取完成, 模板数: {}", templates.size());
        return templates;
    }

    @Override
    @Transactional
    public ReviewTaskEntity createTaskFromTemplate(String templateId, Map<String, Object> params) {
        log.info("从模板创建任务, 模板ID: {}, 参数: {}", templateId, params);
        
        if (StringUtils.isBlank(templateId)) {
            throw new IllegalArgumentException("模板ID不能为空");
        }
        
        // 获取模板
        List<Map<String, Object>> templates = getTaskTemplates();
        Map<String, Object> template = templates.stream()
                .filter(t -> templateId.equals(t.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + templateId));
        
        // 创建任务
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setTaskType((ReviewTaskType) template.get("taskType"));
        task.setPriority((TaskPriority) template.get("priority"));
        task.setEstimatedMinutes((Integer) template.get("estimatedMinutes"));
        
        // 从参数中获取实体信息
        if (params != null) {
            if (params.containsKey("entityType")) {
                task.setEntityType((String) params.get("entityType"));
            }
            if (params.containsKey("entityId")) {
                task.setEntityId(Long.parseLong(params.get("entityId").toString()));
            }
            if (params.containsKey("title")) {
                task.setTitle((String) params.get("title"));
            } else {
                task.setTitle((String) template.get("name"));
            }
            if (params.containsKey("description")) {
                task.setDescription((String) params.get("description"));
            } else {
                task.setDescription((String) template.get("description"));
            }
        } else {
            task.setTitle((String) template.get("name"));
            task.setDescription((String) template.get("description"));
        }
        
        // 验证必填字段
        if (StringUtils.isBlank(task.getEntityType())) {
            throw new IllegalArgumentException("缺少参数: entityType");
        }
        if (task.getEntityId() == null) {
            throw new IllegalArgumentException("缺少参数: entityId");
        }
        
        return createReviewTask(task);
    }

    @Override
    @Transactional
    public String createTaskTemplate(Map<String, Object> template) {
        log.info("创建任务模板, 模板数据: {}", template);
        
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("模板数据不能为空");
        }
        
        // TODO: 在实际系统中，这里应该保存模板到数据库
        // 暂时生成一个模板ID并返回
        
        String templateId = "template_" + System.currentTimeMillis();
        
        log.info("任务模板创建成功, 模板ID: {}", templateId);
        return templateId;
    }

    @Override
    public List<Map<String, Object>> getTaskApprovalHistory(Long taskId) {
        log.info("获取任务审批历史, ID: {}", taskId);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该从审批历史表查询
        // 暂时返回空列表
        
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public Map<String, Object> submitTaskApproval(Long taskId, String userId, String approvalResult, String comments) {
        log.info("提交任务审批, ID: {}, 用户: {}, 结果: {}, 意见: {}", taskId, userId, approvalResult, comments);
        
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(approvalResult)) {
            throw new IllegalArgumentException("审批结果不能为空");
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该实现完整的审批流程
        // 暂时只记录审批并更新任务状态
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("approvalResult", approvalResult);
        result.put("approvedBy", userId);
        result.put("approvalTime", LocalDateTime.now());
        
        // 根据审批结果更新任务
        if ("approve".equals(approvalResult)) {
            task.setStatus("completed");
            task.setResolution("approved");
            task.setCompletedBy(userId);
            task.setCompletedAt(LocalDateTime.now());
        } else if ("reject".equals(approvalResult)) {
            task.setStatus("pending");
            task.setResolution("rejected");
        } else if ("hold".equals(approvalResult)) {
            task.setStatus("pending");
        }
        
        if (StringUtils.isNotBlank(comments)) {
            String newDescription = task.getDescription() != null ?
                    task.getDescription() + "\n\n审批意见 [" + LocalDateTime.now() + "] [" + userId + "]: " + comments :
                    "审批意见 [" + LocalDateTime.now() + "] [" + userId + "]: " + comments;
            task.setDescription(newDescription);
        }
        
        task.setUpdatedAt(LocalDateTime.now());
        reviewTaskMapper.updateById(task);
        
        log.info("任务审批提交成功, ID: {}", taskId);
        return result;
    }

    @Override
    public List<ReviewTaskEntity> getTasksPendingApproval(String userId) {
        log.info("获取待审批的任务, 用户: {}", userId);
        
        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }
        
        // TODO: 在实际系统中，这里应该根据审批流程查询待审批的任务
        // 暂时返回空列表
        
        return Collections.emptyList();
    }

    @Override
    public boolean validateTaskOperation(Long taskId, String userId, String operation) {
        log.info("验证任务操作权限, ID: {}, 用户: {}, 操作: {}", taskId, userId, operation);
        
        if (StringUtils.isBlank(userId)) {
            return false;
        }
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        
        // 基础权限检查
        switch (operation) {
            case "view":
                return true; // 所有人都可以查看
            case "assign":
                return isSupervisor(userId); // 只有主管可以分配任务
            case "start":
                return userId.equals(task.getAssignedTo()) && "assigned".equals(task.getStatus());
            case "complete":
                return userId.equals(task.getAssignedTo()) && "in_progress".equals(task.getStatus());
            case "update":
                return userId.equals(task.getAssignedTo()) || isSupervisor(userId);
            case "delete":
                return isSupervisor(userId);
            case "reassign":
                return isSupervisor(userId);
            case "approve":
                return isApprover(userId, task);
            default:
                return false;
        }
    }

    @Override
    public List<Map<String, Object>> getTaskLogs(Long taskId, int days) {
        log.info("获取任务日志, ID: {}, 天数: {}", taskId, days);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该从日志表查询指定天数的日志
        // 暂时从任务历史中获取
        
        List<Map<String, Object>> logs = getTaskHistory(taskId);
        
        // 过滤指定天数内的日志
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        logs = logs.stream()
                .filter(log -> ((LocalDateTime) log.get("time")).isAfter(cutoff))
                .collect(Collectors.toList());
        
        log.info("任务日志获取完成, 日志数: {}", logs.size());
        return logs;
    }

    @Override
    public boolean sendTaskNotification(Long taskId, String notificationType) {
        log.info("发送任务通知, ID: {}, 通知类型: {}", taskId, notificationType);
        
        ReviewTaskEntity task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在: " + taskId);
        }
        
        // TODO: 在实际系统中，这里应该集成通知系统（邮件、短信、站内信等）
        // 暂时只记录日志
        
        String recipient = null;
        String subject = null;
        String message = null;
        
        switch (notificationType) {
            case "assigned":
                recipient = task.getAssignedTo();
                subject = "新的审核任务分配";
                message = "您有一个新的审核任务: " + task.getTitle();
                break;
            case "due_soon":
                recipient = task.getAssignedTo();
                subject = "任务即将到期提醒";
                message = "您的任务 \"" + task.getTitle() + "\" 即将到期";
                break;
            case "overdue":
                recipient = task.getAssignedTo();
                subject = "任务超时提醒";
                message = "您的任务 \"" + task.getTitle() + "\" 已超时";
                break;
            case "completed":
                // 通知分配人或相关主管
                recipient = task.getAssignedBy() != null ? task.getAssignedBy() : "supervisor";
                subject = "任务已完成";
                message = "任务 \"" + task.getTitle() + "\" 已完成";
                break;
            default:
                log.warn("未知的通知类型: {}", notificationType);
                return false;
        }
        
        if (StringUtils.isNotBlank(recipient)) {
            log.info("发送通知: 收件人={}, 主题={}, 消息={}", recipient, subject, message);
            // 实际发送通知的代码
            return true;
        }
        
        return false;
    }

    // =============== 私有辅助方法 ===============

    /**
     * 验证状态转换是否有效
     */
    private void validateStatusTransition(String fromStatus, String toStatus) {
        // 允许的状态转换
        Map<String, Set<String>> allowedTransitions = new HashMap<>();
        allowedTransitions.put("pending", new HashSet<>(Arrays.asList("assigned", "cancelled")));
        allowedTransitions.put("assigned", new HashSet<>(Arrays.asList("in_progress", "cancelled")));
        allowedTransitions.put("in_progress", new HashSet<>(Arrays.asList("completed", "cancelled")));
        allowedTransitions.put("completed", new HashSet<>(Arrays.asList("pending"))); // 重新打开
        allowedTransitions.put("cancelled", new HashSet<>(Arrays.asList("pending"))); // 重新打开
        
        if (!allowedTransitions.containsKey(fromStatus) || 
                !allowedTransitions.get(fromStatus).contains(toStatus)) {
            throw new IllegalStateException("无效的状态转换: " + fromStatus + " -> " + toStatus);
        }
    }

    /**
     * 获取总任务数
     */
    private int getTotalTaskCount(List<StatusCount> statusCounts) {
        return statusCounts.stream()
                .mapToInt(StatusCount::getCount)
                .sum();
    }

    /**
     * 根据状态获取任务数
     */
    private int getCountByStatus(List<StatusCount> statusCounts, String status) {
        return statusCounts.stream()
                .filter(sc -> status.equals(sc.getStatus()))
                .mapToInt(StatusCount::getCount)
                .sum();
    }

    /**
     * 获取用户任务统计
     */
    private Map<String, Integer> getUserTaskStats(List<AssigneeStatusCount> assigneeCounts, String userId) {
        Map<String, Integer> stats = new HashMap<>();
        
        for (AssigneeStatusCount count : assigneeCounts) {
            if (userId.equals(count.getAssigneeId())) {
                stats.put(count.getStatus(), count.getCount());
            }
        }
        
        // 初始化所有状态
        for (String status : Arrays.asList("assigned", "in_progress", "completed")) {
            stats.putIfAbsent(status, 0);
        }
        
        return stats;
    }

    /**
     * 查找最佳审核人（基于负载均衡）
     */
    private String findBestAssignee() {
        // TODO: 实现负载均衡算法
        // 暂时返回一个固定的审核人
        
        // 在实际系统中，这里应该：
        // 1. 获取所有可用的审核人
        // 2. 计算每个审核人的当前任务数
        // 3. 选择任务最少的审核人
        // 4. 考虑审核人的技能和经验
        
        return "reviewer1";
    }

    /**
     * 检查用户是否是主管
     */
    private boolean isSupervisor(String userId) {
        // TODO: 在实际系统中，这里应该检查用户角色
        // 暂时认为以"supervisor"或"admin"结尾的用户是主管
        return userId.endsWith("supervisor") || userId.endsWith("admin");
    }

    /**
     * 检查用户是否是审批人
     */
    private boolean isApprover(String userId, ReviewTaskEntity task) {
        // TODO: 在实际系统中，这里应该根据审批流程检查
        // 暂时认为主管是审批人
        return isSupervisor(userId);
    }
}