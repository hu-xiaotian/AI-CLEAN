package com.aiclean.service;

import com.aiclean.entity.ReviewTaskEntity;
import com.aiclean.entity.enums.ReviewTaskType;
import com.aiclean.entity.enums.TaskPriority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审核服务接口
 */
public interface ReviewService {
    
    /**
     * 创建审核任务
     * @param task 审核任务
     * @return 创建的审核任务
     */
    ReviewTaskEntity createReviewTask(ReviewTaskEntity task);
    
    /**
     * 批量创建审核任务
     * @param tasks 审核任务列表
     * @return 创建的任务数量
     */
    int batchCreateReviewTasks(List<ReviewTaskEntity> tasks);
    
    /**
     * 更新审核任务
     * @param task 审核任务
     * @return 更新后的审核任务
     */
    ReviewTaskEntity updateReviewTask(ReviewTaskEntity task);
    
    /**
     * 删除审核任务
     * @param taskId 任务ID
     */
    void deleteReviewTask(Long taskId);
    
    /**
     * 根据ID获取审核任务
     * @param taskId 任务ID
     * @return 审核任务
     */
    ReviewTaskEntity getReviewTaskById(Long taskId);
    
    /**
     * 获取我的待办任务
     * @param userId 用户ID
     * @return 任务列表
     */
    List<ReviewTaskEntity> getMyPendingTasks(String userId);
    
    /**
     * 获取分配给我的任务
     * @param userId 用户ID
     * @return 任务列表
     */
    List<ReviewTaskEntity> getAssignedTasks(String userId);
    
    /**
     * 获取我已完成的任务
     * @param userId 用户ID
     * @return 任务列表
     */
    List<ReviewTaskEntity> getMyCompletedTasks(String userId);
    
    /**
     * 获取待分配的任务
     * @return 任务列表
     */
    List<ReviewTaskEntity> getUnassignedTasks();
    
    /**
     * 获取所有任务（分页）
     * @param page 页码
     * @param size 每页大小
     * @return 任务列表
     */
    List<ReviewTaskEntity> getAllTasks(int page, int size);
    
    /**
     * 分配任务
     * @param taskId 任务ID
     * @param assignee 分配给谁
     * @param assigner 分配人
     * @return 分配结果
     */
    ReviewTaskEntity assignTask(Long taskId, String assignee, String assigner);
    
    /**
     * 批量分配任务
     * @param taskIds 任务ID列表
     * @param assignee 分配给谁
     * @param assigner 分配人
     * @return 分配数量
     */
    int batchAssignTasks(List<Long> taskIds, String assignee, String assigner);
    
    /**
     * 重新分配任务
     * @param taskId 任务ID
     * @param newAssignee 新的负责人
     * @param reassigner 重新分配人
     * @return 重新分配结果
     */
    ReviewTaskEntity reassignTask(Long taskId, String newAssignee, String reassigner);
    
    /**
     * 开始处理任务
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 任务状态
     */
    ReviewTaskEntity startTask(Long taskId, String userId);
    
    /**
     * 完成任务
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param resolution 解决方式
     * @param comment 解决说明
     * @return 完成的任务
     */
    ReviewTaskEntity completeTask(Long taskId, String userId, String resolution, String comment);
    
    /**
     * 批量完成任务
     * @param taskIds 任务ID列表
     * @param userId 用户ID
     * @param resolution 解决方式
     * @param comment 解决说明
     * @return 完成数量
     */
    int batchCompleteTasks(List<Long> taskIds, String userId, String resolution, String comment);
    
    /**
     * 取消任务
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param reason 取消原因
     * @return 取消的任务
     */
    ReviewTaskEntity cancelTask(Long taskId, String userId, String reason);
    
    /**
     * 重新打开任务
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param reason 重新打开原因
     * @return 重新打开的任务
     */
    ReviewTaskEntity reopenTask(Long taskId, String userId, String reason);
    
    /**
     * 更新任务优先级
     * @param taskId 任务ID
     * @param priority 新的优先级
     * @return 更新后的任务
     */
    ReviewTaskEntity updateTaskPriority(Long taskId, TaskPriority priority);
    
    /**
     * 更新任务截止日期
     * @param taskId 任务ID
     * @param dueDate 新的截止日期
     * @return 更新后的任务
     */
    ReviewTaskEntity updateTaskDueDate(Long taskId, LocalDateTime dueDate);
    
    /**
     * 添加任务评论
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param comment 评论内容
     * @return 任务评论ID
     */
    Long addTaskComment(Long taskId, String userId, String comment);
    
    /**
     * 获取任务历史
     * @param taskId 任务ID
     * @return 历史记录列表
     */
    List<Map<String, Object>> getTaskHistory(Long taskId);
    
    /**
     * 搜索任务
     * @param keyword 关键字
     * @param status 状态
     * @param priority 优先级
     * @param assignee 负责人
     * @param taskType 任务类型
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 任务列表
     */
    List<ReviewTaskEntity> searchTasks(String keyword, String status, TaskPriority priority, 
                                       String assignee, ReviewTaskType taskType, 
                                       LocalDateTime startDate, LocalDateTime endDate);
    
    /**
     * 根据数据ID获取关联的任务
     * @param entityType 实体类型
     * @param entityId 实体ID
     * @return 任务列表
     */
    List<ReviewTaskEntity> getTasksByEntity(String entityType, Long entityId);
    
    /**
     * 获取任务统计信息
     * @param userId 用户ID（null表示所有用户）
     * @return 统计信息
     */
    Map<String, Object> getTaskStatistics(String userId);
    
    /**
     * 获取团队工作负载
     * @return 工作负载统计
     */
    Map<String, Object> getTeamWorkload();
    
    /**
     * 获取超时任务
     * @return 超时任务列表
     */
    List<ReviewTaskEntity> getOverdueTasks();
    
    /**
     * 获取即将到期的任务
     * @param hours 小时数
     * @return 即将到期的任务列表
     */
    List<ReviewTaskEntity> getUpcomingDueTasks(int hours);
    
    /**
     * 获取高优先级任务
     * @return 高优先级任务列表
     */
    List<ReviewTaskEntity> getHighPriorityTasks();
    
    /**
     * 自动分配任务（基于负载均衡）
     * @param taskId 任务ID
     * @return 自动分配结果
     */
    ReviewTaskEntity autoAssignTask(Long taskId);
    
    /**
     * 批量自动分配任务
     * @param taskIds 任务ID列表
     * @return 自动分配数量
     */
    int batchAutoAssignTasks(List<Long> taskIds);
    
    /**
     * 创建数据质量检查任务
     * @param entityType 实体类型
     * @param entityId 实体ID
     * @param issues 问题列表
     * @param priority 优先级
     * @return 创建的审核任务
     */
    ReviewTaskEntity createQualityCheckTask(String entityType, Long entityId, 
                                           List<Map<String, Object>> issues, TaskPriority priority);
    
    /**
     * 创建字段映射审核任务
     * @param mappingId 映射ID
     * @param confidence 置信度
     * @param suggestions 建议列表
     * @return 创建的审核任务
     */
    ReviewTaskEntity createFieldMappingAuditTask(Long mappingId, Double confidence, 
                                                List<Map<String, Object>> suggestions);
    
    /**
     * 创建分类审核任务
     * @param dataId 数据ID
     * @param categoryId 分类ID
     * @param confidence 置信度
     * @param alternativeCategories 备选分类
     * @return 创建的审核任务
     */
    ReviewTaskEntity createCategoryAuditTask(Long dataId, Long categoryId, Double confidence,
                                           List<Long> alternativeCategories);
    
    /**
     * 导出任务报告
     * @param format 导出格式（excel, csv, pdf）
     * @param filters 过滤条件
     * @return 导出文件路径
     */
    String exportTaskReport(String format, Map<String, Object> filters);
    
    /**
     * 获取任务提醒
     * @param userId 用户ID
     * @return 提醒列表
     */
    List<Map<String, Object>> getTaskReminders(String userId);
    
    /**
     * 标记任务为已读
     * @param taskId 任务ID
     * @param userId 用户ID
     */
    void markTaskAsRead(Long taskId, String userId);
    
    /**
     * 设置任务关注
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param watching 是否关注
     */
    void setTaskWatching(Long taskId, String userId, boolean watching);
    
    /**
     * 获取我关注的任务
     * @param userId 用户ID
     * @return 任务列表
     */
    List<ReviewTaskEntity> getWatchedTasks(String userId);
    
    /**
     * 获取任务讨论
     * @param taskId 任务ID
     * @return 讨论记录
     */
    List<Map<String, Object>> getTaskDiscussions(Long taskId);
    
    /**
     * 添加任务附件
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param fileName 文件名
     * @param fileData 文件数据
     * @param fileType 文件类型
     * @return 附件ID
     */
    Long addTaskAttachment(Long taskId, String userId, String fileName, 
                          byte[] fileData, String fileType);
    
    /**
     * 获取任务附件
     * @param taskId 任务ID
     * @return 附件列表
     */
    List<Map<String, Object>> getTaskAttachments(Long taskId);
    
    /**
     * 删除任务附件
     * @param attachmentId 附件ID
     * @param userId 用户ID
     */
    void deleteTaskAttachment(Long attachmentId, String userId);
    
    /**
     * 获取任务模板
     * @return 任务模板列表
     */
    List<Map<String, Object>> getTaskTemplates();
    
    /**
     * 从模板创建任务
     * @param templateId 模板ID
     * @param params 模板参数
     * @return 创建的任务
     */
    ReviewTaskEntity createTaskFromTemplate(String templateId, Map<String, Object> params);
    
    /**
     * 创建任务模板
     * @param template 模板数据
     * @return 模板ID
     */
    String createTaskTemplate(Map<String, Object> template);
    
    /**
     * 获取任务审批历史
     * @param taskId 任务ID
     * @return 审批历史
     */
    List<Map<String, Object>> getTaskApprovalHistory(Long taskId);
    
    /**
     * 提交任务审批
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param approvalResult 审批结果（approve, reject, hold）
     * @param comments 审批意见
     * @return 审批结果
     */
    Map<String, Object> submitTaskApproval(Long taskId, String userId, String approvalResult, String comments);
    
    /**
     * 获取待审批的任务
     * @param userId 用户ID
     * @return 待审批任务列表
     */
    List<ReviewTaskEntity> getTasksPendingApproval(String userId);
    
    /**
     * 验证任务操作权限
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param operation 操作类型
     * @return 是否允许操作
     */
    boolean validateTaskOperation(Long taskId, String userId, String operation);
    
    /**
     * 获取任务日志
     * @param taskId 任务ID
     * @param days 天数
     * @return 日志列表
     */
    List<Map<String, Object>> getTaskLogs(Long taskId, int days);
    
    /**
     * 发送任务通知
     * @param taskId 任务ID
     * @param notificationType 通知类型
     * @return 通知结果
     */
    boolean sendTaskNotification(Long taskId, String notificationType);
}