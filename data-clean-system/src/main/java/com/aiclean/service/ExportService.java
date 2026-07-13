package com.aiclean.service;

import com.aiclean.entity.ExportBatchEntity;

import java.util.List;
import java.util.Map;

/**
 * 导出服务接口
 */
public interface ExportService {
    
    /**
     * 创建导出任务
     * @param batch 导出批次信息
     * @return 创建的导出批次
     */
    ExportBatchEntity createExportBatch(ExportBatchEntity batch);
    
    /**
     * 根据分类导出数据
     * @param categoryIds 分类ID列表
     * @param format 导出格式（excel, csv, json）
     * @param includeColumns 包含的列
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportByCategories(List<Long> categoryIds, String format, 
                                        List<String> includeColumns, String userId);
    
    /**
     * 根据状态导出数据
     * @param statusList 状态列表
     * @param format 导出格式
     * @param includeColumns 包含的列
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportByStatus(List<String> statusList, String format,
                                    List<String> includeColumns, String userId);
    
    /**
     * 自定义条件导出数据
     * @param filters 过滤条件
     * @param format 导出格式
     * @param includeColumns 包含的列
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportByCustomFilters(Map<String, Object> filters, String format,
                                           List<String> includeColumns, String userId);
    
    /**
     * 执行导出任务
     * @param batchId 批次ID
     * @return 导出结果
     */
    ExportBatchEntity executeExport(Long batchId);
    
    /**
     * 执行异步导出任务
     * @param batchId 批次ID
     * @return 是否成功启动
     */
    boolean executeExportAsync(Long batchId);
    
    /**
     * 取消导出任务
     * @param batchId 批次ID
     * @return 是否成功取消
     */
    boolean cancelExport(Long batchId);
    
    /**
     * 删除导出批次
     * @param batchId 批次ID
     */
    void deleteExportBatch(Long batchId);
    
    /**
     * 根据ID获取导出批次
     * @param batchId 批次ID
     * @return 导出批次
     */
    ExportBatchEntity getExportBatchById(Long batchId);
    
    /**
     * 获取我的导出历史
     * @param userId 用户ID
     * @param page 页码
     * @param size 每页大小
     * @return 导出批次列表
     */
    List<ExportBatchEntity> getMyExportHistory(String userId, int page, int size);
    
    /**
     * 获取所有导出历史
     * @param page 页码
     * @param size 每页大小
     * @return 导出批次列表
     */
    List<ExportBatchEntity> getAllExportHistory(int page, int size);
    
    /**
     * 获取进行中的导出任务
     * @param userId 用户ID
     * @return 导出批次列表
     */
    List<ExportBatchEntity> getInProgressExports(String userId);
    
    /**
     * 获取失败的导出任务
     * @param userId 用户ID
     * @param days 天数
     * @return 导出批次列表
     */
    List<ExportBatchEntity> getFailedExports(String userId, int days);
    
    /**
     * 重试失败的导出任务
     * @param batchId 批次ID
     * @return 重试结果
     */
    ExportBatchEntity retryFailedExport(Long batchId);
    
    /**
     * 下载导出文件
     * @param batchId 批次ID
     * @return 文件路径
     */
    String downloadExportFile(Long batchId);
    
    /**
     * 获取导出统计信息
     * @param userId 用户ID
     * @param days 天数
     * @return 统计信息
     */
    Map<String, Object> getExportStatistics(String userId, int days);
    
    /**
     * 清理旧的导出文件
     * @param days 保留天数
     * @return 清理的文件数
     */
    int cleanupOldExports(int days);
    
    /**
     * 获取支持的导出格式
     * @return 支持的格式列表
     */
    List<Map<String, Object>> getSupportedFormats();
    
    /**
     * 获取可导出的字段
     * @param exportType 导出类型
     * @return 字段列表
     */
    List<Map<String, Object>> getExportableFields(String exportType);
    
    /**
     * 验证导出参数
     * @param batch 导出批次
     * @return 验证结果
     */
    Map<String, Object> validateExportParameters(ExportBatchEntity batch);
    
    /**
     * 生成导出预览
     * @param batchId 批次ID
     * @return 预览数据
     */
    Map<String, Object> generateExportPreview(Long batchId);
    
    /**
     * 复制导出任务
     * @param batchId 批次ID
     * @param newName 新批次名称
     * @param userId 用户ID
     * @return 复制的批次
     */
    ExportBatchEntity copyExportBatch(Long batchId, String newName, String userId);
    
    /**
     * 创建导出模板
     * @param templateName 模板名称
     * @param templateData 模板数据
     * @param userId 用户ID
     * @return 模板ID
     */
    String createExportTemplate(String templateName, Map<String, Object> templateData, String userId);
    
    /**
     * 获取导出模板
     * @param userId 用户ID
     * @return 模板列表
     */
    List<Map<String, Object>> getExportTemplates(String userId);
    
    /**
     * 从模板创建导出任务
     * @param templateId 模板ID
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity createExportFromTemplate(String templateId, String userId);
    
    /**
     * 批量导出
     * @param batchIds 批次ID列表
     * @return 批量导出结果
     */
    Map<String, Object> batchExport(List<Long> batchIds);
    
    /**
     * 计划导出任务
     * @param batchId 批次ID
     * @param scheduleTime 计划时间
     * @return 计划结果
     */
    Map<String, Object> scheduleExport(Long batchId, java.time.LocalDateTime scheduleTime);
    
    /**
     * 获取计划中的导出任务
     * @param userId 用户ID
     * @return 计划任务列表
     */
    List<Map<String, Object>> getScheduledExports(String userId);
    
    /**
     * 取消计划导出任务
     * @param scheduleId 计划ID
     * @return 取消结果
     */
    boolean cancelScheduledExport(String scheduleId);
    
    /**
     * 导出到多个文件（分片）
     * @param batchId 批次ID
     * @param maxRecordsPerFile 每个文件最大记录数
     * @return 文件列表
     */
    List<String> exportToMultipleFiles(Long batchId, int maxRecordsPerFile);
    
    /**
     * 压缩导出文件
     * @param batchId 批次ID
     * @return 压缩文件路径
     */
    String compressExportFiles(Long batchId);
    
    /**
     * 发送导出通知
     * @param batchId 批次ID
     * @param notificationType 通知类型
     * @return 通知结果
     */
    boolean sendExportNotification(Long batchId, String notificationType);
    
    /**
     * 记录导出日志
     * @param batchId 批次ID
     * @param action 操作
     * @param details 详情
     * @param userId 用户ID
     */
    void logExportAction(Long batchId, String action, String details, String userId);
    
    /**
     * 获取导出日志
     * @param batchId 批次ID
     * @return 日志列表
     */
    List<Map<String, Object>> getExportLogs(Long batchId);
    
    /**
     * 验证导出权限
     * @param batchId 批次ID
     * @param userId 用户ID
     * @return 是否有权限
     */
    boolean validateExportPermission(Long batchId, String userId);
    
    /**
     * 设置导出水印
     * @param batchId 批次ID
     * @param watermark 水印文本
     * @return 设置结果
     */
    boolean setExportWatermark(Long batchId, String watermark);
    
    /**
     * 加密导出文件
     * @param batchId 批次ID
     * @param password 密码
     * @return 加密结果
     */
    boolean encryptExportFile(Long batchId, String password);
    
    /**
     * 导出到外部系统
     * @param batchId 批次ID
     * @param targetSystem 目标系统
     * @param config 配置
     * @return 导出结果
     */
    Map<String, Object> exportToExternalSystem(Long batchId, String targetSystem, Map<String, Object> config);
    
    /**
     * 生成导出报表
     * @param batchId 批次ID
     * @param reportType 报表类型
     * @return 报表路径
     */
    String generateExportReport(Long batchId, String reportType);
    
    /**
     * 验证导出文件完整性
     * @param batchId 批次ID
     * @return 验证结果
     */
    Map<String, Object> validateExportFile(Long batchId);
    
    /**
     * 合并多个导出批次
     * @param batchIds 批次ID列表
     * @param mergeType 合并类型
     * @param userId 用户ID
     * @return 合并结果
     */
    ExportBatchEntity mergeExportBatches(List<Long> batchIds, String mergeType, String userId);
    
    /**
     * 获取导出任务进度
     * @param batchId 批次ID
     * @return 进度信息
     */
    Map<String, Object> getExportProgress(Long batchId);
    
    /**
     * 暂停导出任务
     * @param batchId 批次ID
     * @return 暂停结果
     */
    boolean pauseExport(Long batchId);
    
    /**
     * 恢复导出任务
     * @param batchId 批次ID
     * @return 恢复结果
     */
    boolean resumeExport(Long batchId);
    
    /**
     * 设置导出优先级
     * @param batchId 批次ID
     * @param priority 优先级
     * @return 设置结果
     */
    boolean setExportPriority(Long batchId, int priority);
    
    /**
     * 获取导出队列状态
     * @return 队列状态
     */
    Map<String, Object> getExportQueueStatus();
    
    /**
     * 导出分类结构
     * @param categoryIds 分类ID列表
     * @param format 导出格式
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportCategoryStructure(List<Long> categoryIds, String format, String userId);
    
    /**
     * 导出审核任务
     * @param taskIds 任务ID列表
     * @param format 导出格式
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportReviewTasks(List<Long> taskIds, String format, String userId);
    
    /**
     * 导出数据质量报告
     * @param dataIds 数据ID列表
     * @param format 导出格式
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportQualityReport(List<Long> dataIds, String format, String userId);
    
    /**
     * 导出系统日志
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param format 导出格式
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportSystemLogs(java.time.LocalDateTime startDate, 
                                      java.time.LocalDateTime endDate, 
                                      String format, String userId);
    
    /**
     * 导出字段映射关系
     * @param mappingIds 映射ID列表
     * @param format 导出格式
     * @param userId 用户ID
     * @return 导出批次
     */
    ExportBatchEntity exportFieldMappings(List<Long> mappingIds, String format, String userId);
}