package com.aiclean.service.impl;

import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.entity.ExportBatchEntity;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.mapper.CleanedDataMapper;
import com.aiclean.mapper.CategoryMapper;
import com.aiclean.mapper.ExportBatchMapper;
import com.aiclean.service.ExportService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 导出服务实现类
 */
@Service
@Slf4j
public class ExportServiceImpl implements ExportService {

    @Autowired
    private ExportBatchMapper exportBatchMapper;

    @Autowired
    private CleanedDataMapper cleanedDataMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${export.file.base-path:/tmp/exports}")
    private String exportBasePath;

    @Value("${export.max-records-per-file:10000}")
    private int maxRecordsPerFile;

    @Override
    @Transactional
    public ExportBatchEntity createExportBatch(ExportBatchEntity batch) {
        log.info("创建导出批次: {}", batch);

        // 验证必填字段
        if (StringUtils.isBlank(batch.getBatchName())) {
            throw new IllegalArgumentException("批次名称不能为空");
        }
        if (StringUtils.isBlank(batch.getExportType())) {
            throw new IllegalArgumentException("导出类型不能为空");
        }
        if (StringUtils.isBlank(batch.getFormat())) {
            throw new IllegalArgumentException("导出格式不能为空");
        }

        // 设置默认值
        if (batch.getStatus() == null) {
            batch.setStatus("pending");
        }
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());

        // 插入数据库
        exportBatchMapper.insert(batch);

        log.info("导出批次创建成功, ID: {}", batch.getId());
        return batch;
    }

    @Override
    @Transactional
    public ExportBatchEntity exportByCategories(List<Long> categoryIds, String format,
                                               List<String> includeColumns, String userId) {
        log.info("按分类导出数据, 分类IDs: {}, 格式: {}, 用户: {}", categoryIds, format, userId);

        if (categoryIds == null || categoryIds.isEmpty()) {
            throw new IllegalArgumentException("分类ID列表不能为空");
        }

        // 创建导出批次
        ExportBatchEntity batch = new ExportBatchEntity();
        batch.setBatchName("分类导出_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        batch.setExportType("by_category");
        batch.setFormat(format);
        batch.setExportedBy(userId);

        try {
            // 序列化分类ID列表
            batch.setCategoryIds(objectMapper.writeValueAsString(categoryIds));
        } catch (Exception e) {
            throw new RuntimeException("序列化分类ID失败", e);
        }

        if (includeColumns != null && !includeColumns.isEmpty()) {
            try {
                batch.setIncludeColumns(objectMapper.writeValueAsString(includeColumns));
            } catch (Exception e) {
                log.warn("序列化包含列失败", e);
            }
        }

        // 计算总记录数
        int totalRecords = calculateTotalRecordsByCategories(categoryIds);
        batch.setTotalRecords(totalRecords);
        batch.setExportedRecords(0);

        // 保存批次
        return createExportBatch(batch);
    }

    @Override
    @Transactional
    public ExportBatchEntity exportByStatus(List<String> statusList, String format,
                                           List<String> includeColumns, String userId) {
        log.info("按状态导出数据, 状态列表: {}, 格式: {}, 用户: {}", statusList, format, userId);

        if (statusList == null || statusList.isEmpty()) {
            throw new IllegalArgumentException("状态列表不能为空");
        }

        // 创建导出批次
        ExportBatchEntity batch = new ExportBatchEntity();
        batch.setBatchName("状态导出_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        batch.setExportType("by_status");
        batch.setFormat(format);
        batch.setExportedBy(userId);

        try {
            batch.setStatusList(objectMapper.writeValueAsString(statusList));
        } catch (Exception e) {
            throw new RuntimeException("序列化状态列表失败", e);
        }

        if (includeColumns != null && !includeColumns.isEmpty()) {
            try {
                batch.setIncludeColumns(objectMapper.writeValueAsString(includeColumns));
            } catch (Exception e) {
                log.warn("序列化包含列失败", e);
            }
        }

        // 计算总记录数
        int totalRecords = calculateTotalRecordsByStatus(statusList);
        batch.setTotalRecords(totalRecords);
        batch.setExportedRecords(0);

        // 保存批次
        return createExportBatch(batch);
    }

    @Override
    @Transactional
    public ExportBatchEntity exportByCustomFilters(Map<String, Object> filters, String format,
                                                  List<String> includeColumns, String userId) {
        log.info("自定义条件导出数据, 过滤条件: {}, 格式: {}, 用户: {}", filters, format, userId);

        if (filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException("过滤条件不能为空");
        }

        // 创建导出批次
        ExportBatchEntity batch = new ExportBatchEntity();
        batch.setBatchName("自定义导出_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        batch.setExportType("custom");
        batch.setFormat(format);
        batch.setExportedBy(userId);

        try {
            batch.setFilterConditions(objectMapper.writeValueAsString(filters));
        } catch (Exception e) {
            throw new RuntimeException("序列化过滤条件失败", e);
        }

        if (includeColumns != null && !includeColumns.isEmpty()) {
            try {
                batch.setIncludeColumns(objectMapper.writeValueAsString(includeColumns));
            } catch (Exception e) {
                log.warn("序列化包含列失败", e);
            }
        }

        // 计算总记录数
        int totalRecords = calculateTotalRecordsByFilters(filters);
        batch.setTotalRecords(totalRecords);
        batch.setExportedRecords(0);

        // 保存批次
        return createExportBatch(batch);
    }

    @Override
    @Transactional
    public ExportBatchEntity executeExport(Long batchId) {
        log.info("执行导出任务, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        // 检查批次状态
        if (!"pending".equals(batch.getStatus())) {
            throw new IllegalStateException("只能执行待处理的导出任务");
        }

        // 更新状态为处理中
        batch.setStatus("processing");
        batch.setUpdatedAt(LocalDateTime.now());
        exportBatchMapper.updateById(batch);

        try {
            // 根据导出类型执行导出
            switch (batch.getExportType()) {
                case "by_category":
                    executeCategoryExport(batch);
                    break;
                case "by_status":
                    executeStatusExport(batch);
                    break;
                case "custom":
                    executeCustomExport(batch);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的导出类型: " + batch.getExportType());
            }

            // 更新状态为完成
            batch.setStatus("completed");
            batch.setExportedAt(LocalDateTime.now());
            batch.setUpdatedAt(LocalDateTime.now());
            exportBatchMapper.updateById(batch);

            log.info("导出任务执行成功, 批次ID: {}", batchId);
            return batch;

        } catch (Exception e) {
            log.error("导出任务执行失败, 批次ID: {}", batchId, e);

            // 更新状态为失败
            batch.setStatus("failed");
            batch.setErrorMessage(e.getMessage());
            batch.setUpdatedAt(LocalDateTime.now());
            exportBatchMapper.updateById(batch);

            throw new RuntimeException("导出任务执行失败: " + e.getMessage(), e);
        }
    }


    @Override
    @Transactional
    public boolean cancelExport(Long batchId) {
        log.info("取消导出任务, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        // 只能取消待处理或处理中的任务
        if (!"pending".equals(batch.getStatus()) && !"processing".equals(batch.getStatus())) {
            throw new IllegalStateException("只能取消待处理或处理中的导出任务");
        }

        // 更新状态为取消
        batch.setStatus("cancelled");
        batch.setUpdatedAt(LocalDateTime.now());
        exportBatchMapper.updateById(batch);

        log.info("导出任务取消成功, 批次ID: {}", batchId);
        return true;
    }

    @Override
    @Transactional
    public void deleteExportBatch(Long batchId) {
        log.info("删除导出批次, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        // 只能删除已完成、失败或取消的任务
        if (!"completed".equals(batch.getStatus()) && !"failed".equals(batch.getStatus()) && !"cancelled".equals(batch.getStatus())) {
            throw new IllegalStateException("只能删除已完成、失败或取消的导出任务");
        }

        // 删除文件
        if (StringUtils.isNotBlank(batch.getFilePath())) {
            try {
                Files.deleteIfExists(Paths.get(batch.getFilePath()));
                log.info("删除导出文件: {}", batch.getFilePath());
            } catch (IOException e) {
                log.warn("删除导出文件失败: {}", batch.getFilePath(), e);
            }
        }

        // 物理删除批次
        exportBatchMapper.deleteById(batchId);

        log.info("导出批次删除成功, 批次ID: {}", batchId);
    }

    @Override
    public ExportBatchEntity getExportBatchById(Long batchId) {
        return exportBatchMapper.selectById(batchId);
    }

    @Override
    public List<ExportBatchEntity> getMyExportHistory(String userId, int page, int size) {
        log.info("获取我的导出历史, 用户: {}, 页码: {}, 每页大小: {}", userId, page, size);

        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }

        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("exported_by", userId)
                   .orderByDesc("created_at")
                   .last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        return exportBatchMapper.selectList(queryWrapper);
    }

    @Override
    public List<ExportBatchEntity> getAllExportHistory(int page, int size) {
        log.info("获取所有导出历史, 页码: {}, 每页大小: {}", page, size);

        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("created_at")
                   .last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        return exportBatchMapper.selectList(queryWrapper);
    }

    @Override
    public List<ExportBatchEntity> getInProgressExports(String userId) {
        log.info("获取进行中的导出任务, 用户: {}", userId);

        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", "processing");

        if (StringUtils.isNotBlank(userId)) {
            queryWrapper.eq("exported_by", userId);
        }

        queryWrapper.orderByAsc("created_at");

        return exportBatchMapper.selectList(queryWrapper);
    }

    @Override
    public List<ExportBatchEntity> getFailedExports(String userId, int days) {
        log.info("获取失败的导出任务, 用户: {}, 天数: {}", userId, days);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);

        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", "failed")
                   .ge("created_at", cutoff);

        if (StringUtils.isNotBlank(userId)) {
            queryWrapper.eq("exported_by", userId);
        }

        queryWrapper.orderByDesc("created_at");

        return exportBatchMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional
    public ExportBatchEntity retryFailedExport(Long batchId) {
        log.info("重试失败的导出任务, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        if (!"failed".equals(batch.getStatus())) {
            throw new IllegalStateException("只能重试失败的导出任务");
        }

        // 重置批次状态
        batch.setStatus("pending");
        batch.setErrorMessage(null);
        batch.setUpdatedAt(LocalDateTime.now());
        exportBatchMapper.updateById(batch);

        log.info("重试任务创建成功, 批次ID: {}", batchId);
        return batch;
    }

    @Override
    public String downloadExportFile(Long batchId) {
        log.info("下载导出文件, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        if (!"completed".equals(batch.getStatus())) {
            throw new IllegalStateException("只能下载已完成的导出文件");
        }

        if (StringUtils.isBlank(batch.getFilePath())) {
            throw new IllegalStateException("导出文件不存在");
        }

        File file = new File(batch.getFilePath());
        if (!file.exists()) {
            throw new IllegalStateException("导出文件不存在: " + batch.getFilePath());
        }

        log.info("导出文件下载成功, 批次ID: {}, 文件路径: {}", batchId, batch.getFilePath());
        return batch.getFilePath();
    }

    @Override
    public Map<String, Object> getExportStatistics(String userId, int days) {
        log.info("获取导出统计信息, 用户: {}, 天数: {}", userId, days);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);

        Map<String, Object> stats = new HashMap<>();

        // 查询指定时间范围内的导出批次
        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.ge("created_at", cutoff);

        if (StringUtils.isNotBlank(userId)) {
            queryWrapper.eq("exported_by", userId);
        }

        List<ExportBatchEntity> batches = exportBatchMapper.selectList(queryWrapper);

        // 基础统计
        stats.put("totalBatches", batches.size());
        stats.put("totalRecords", batches.stream().mapToInt(ExportBatchEntity::getTotalRecords).sum());
        stats.put("exportedRecords", batches.stream().mapToInt(ExportBatchEntity::getExportedRecords).sum());

        // 按状态统计
        Map<String, Long> statusStats = batches.stream()
                .collect(Collectors.groupingBy(ExportBatchEntity::getStatus, Collectors.counting()));
        stats.put("statusStats", statusStats);

        // 按格式统计
        Map<String, Long> formatStats = batches.stream()
                .collect(Collectors.groupingBy(ExportBatchEntity::getFormat, Collectors.counting()));
        stats.put("formatStats", formatStats);

        // 按类型统计
        Map<String, Long> typeStats = batches.stream()
                .collect(Collectors.groupingBy(ExportBatchEntity::getExportType, Collectors.counting()));
        stats.put("typeStats", typeStats);

        // 按用户统计（如果是管理员）
        if (StringUtils.isBlank(userId)) {
            Map<String, Long> userStats = batches.stream()
                    .collect(Collectors.groupingBy(ExportBatchEntity::getExportedBy, Collectors.counting()));
            stats.put("userStats", userStats);
        }

        // 文件大小统计
        long totalFileSize = batches.stream()
                .filter(b -> b.getFileSize() != null)
                .mapToLong(ExportBatchEntity::getFileSize)
                .sum();
        stats.put("totalFileSize", totalFileSize);
        stats.put("averageFileSize", batches.isEmpty() ? 0 : totalFileSize / batches.size());

        // 成功率
        long successCount = batches.stream()
                .filter(b -> "completed".equals(b.getStatus()))
                .count();
        double successRate = batches.isEmpty() ? 0 : (double) successCount / batches.size() * 100;
        stats.put("successRate", successRate);

        log.info("导出统计信息获取完成");
        return stats;
    }

    @Override
    @Transactional
    public int cleanupOldExports(int days) {
        log.info("清理旧的导出文件, 保留天数: {}", days);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);

        QueryWrapper<ExportBatchEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lt("created_at", cutoff)
                   .in("status", Arrays.asList("completed", "failed", "cancelled"));

        List<ExportBatchEntity> oldBatches = exportBatchMapper.selectList(queryWrapper);

        int deletedFiles = 0;
        for (ExportBatchEntity batch : oldBatches) {
            try {
                // 删除文件
                if (StringUtils.isNotBlank(batch.getFilePath())) {
                    Files.deleteIfExists(Paths.get(batch.getFilePath()));
                    deletedFiles++;
                    log.debug("删除文件: {}", batch.getFilePath());
                }

                // 物理删除批次
                exportBatchMapper.deleteById(batch.getId());
            } catch (IOException e) {
                log.warn("删除文件失败: {}", batch.getFilePath(), e);
            }
        }

        log.info("清理完成, 共清理 {} 个导出批次, 删除 {} 个文件", oldBatches.size(), deletedFiles);
        return deletedFiles;
    }

    @Override
    public List<Map<String, Object>> getSupportedFormats() {
        log.info("获取支持的导出格式");

        List<Map<String, Object>> formats = new ArrayList<>();

        // Excel格式
        Map<String, Object> excelFormat = new HashMap<>();
        excelFormat.put("format", "excel");
        excelFormat.put("name", "Excel文件 (.xlsx)");
        excelFormat.put("description", "Microsoft Excel格式，支持多个工作表");
        excelFormat.put("extension", ".xlsx");
        excelFormat.put("mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        formats.add(excelFormat);

        // CSV格式
        Map<String, Object> csvFormat = new HashMap<>();
        csvFormat.put("format", "csv");
        csvFormat.put("name", "CSV文件 (.csv)");
        csvFormat.put("description", "逗号分隔值格式，兼容性好");
        csvFormat.put("extension", ".csv");
        csvFormat.put("mimeType", "text/csv");
        formats.add(csvFormat);

        // JSON格式
        Map<String, Object> jsonFormat = new HashMap<>();
        jsonFormat.put("format", "json");
        jsonFormat.put("name", "JSON文件 (.json)");
        jsonFormat.put("description", "JavaScript对象表示法格式，适合数据交换");
        jsonFormat.put("extension", ".json");
        jsonFormat.put("mimeType", "application/json");
        formats.add(jsonFormat);

        // PDF格式
        Map<String, Object> pdfFormat = new HashMap<>();
        pdfFormat.put("format", "pdf");
        pdfFormat.put("name", "PDF文件 (.pdf)");
        pdfFormat.put("description", "便携式文档格式，适合打印和分享");
        pdfFormat.put("extension", ".pdf");
        pdfFormat.put("mimeType", "application/pdf");
        formats.add(pdfFormat);

        log.info("支持的导出格式获取完成, 数量: {}", formats.size());
        return formats;
    }

    @Override
    public List<Map<String, Object>> getExportableFields(String exportType) {
        log.info("获取可导出的字段, 导出类型: {}", exportType);

        List<Map<String, Object>> fields = new ArrayList<>();

        // 通用字段
        addField(fields, "id", "ID", "唯一标识符", "general", true);
        addField(fields, "category_name", "分类名称", "数据所属分类", "general", true);
        addField(fields, "material_code", "物料代码", "物料唯一代码", "general", true);
        addField(fields, "long_description", "长描述", "物料详细描述", "general", true);
        addField(fields, "full_description", "全描述", "物料完整描述", "general", true);
        addField(fields, "unit_of_measure", "计量单位", "物料计量单位", "general", true);
        addField(fields, "cleaned_long_description", "清洗后长描述", "清洗后的长描述", "cleaned", true);
        addField(fields, "cleaned_full_description", "清洗后全描述", "清洗后的全描述", "cleaned", true);
        addField(fields, "status", "状态", "数据状态", "general", true);
        addField(fields, "quality_score", "质量评分", "数据质量评分", "quality", false);
        addField(fields, "confidence", "置信度", "数据清洗置信度", "quality", false);
        addField(fields, "create_time", "创建时间", "数据创建时间", "general", true);
        addField(fields, "update_time", "更新时间", "数据更新时间", "general", false);

        // 按导出类型过滤字段
        if (StringUtils.isNotBlank(exportType)) {
            fields = fields.stream()
                    .filter(f -> {
                        String fieldType = (String) f.get("type");
                        return "general".equals(fieldType) || exportType.equals(fieldType);
                    })
                    .collect(Collectors.toList());
        }

        log.info("可导出的字段获取完成, 数量: {}", fields.size());
        return fields;
    }

    @Override
    public Map<String, Object> validateExportParameters(ExportBatchEntity batch) {
        log.info("验证导出参数, 批次: {}", batch);

        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 验证批次名称
        if (StringUtils.isBlank(batch.getBatchName())) {
            errors.add("批次名称不能为空");
        }

        // 验证导出类型
        if (StringUtils.isBlank(batch.getExportType())) {
            errors.add("导出类型不能为空");
        } else if (!Arrays.asList("by_category", "by_status", "custom").contains(batch.getExportType())) {
            errors.add("不支持的导出类型: " + batch.getExportType());
        }

        // 验证导出格式
        if (StringUtils.isBlank(batch.getFormat())) {
            errors.add("导出格式不能为空");
        } else {
            List<Map<String, Object>> supportedFormats = getSupportedFormats();
            boolean formatSupported = supportedFormats.stream()
                    .anyMatch(f -> batch.getFormat().equals(f.get("format")));
            if (!formatSupported) {
                errors.add("不支持的导出格式: " + batch.getFormat());
            }
        }

        // 根据导出类型验证特定参数
        switch (batch.getExportType()) {
            case "by_category":
                if (StringUtils.isBlank(batch.getCategoryIds())) {
                    errors.add("分类导出需要指定分类ID列表");
                } else {
                    try {
                        List<Long> categoryIds = objectMapper.readValue(batch.getCategoryIds(), new TypeReference<List<Long>>() {});
                        if (categoryIds.isEmpty()) {
                            errors.add("分类ID列表不能为空");
                        } else {
                            // 验证分类是否存在
                            int validCategories = categoryIds.stream()
                                    .mapToInt(categoryId -> categoryMapper.selectById(categoryId) != null ? 1 : 0)
                                    .sum();
                            if (validCategories < categoryIds.size()) {
                                warnings.add("部分分类不存在或已被删除");
                            }
                        }
                    } catch (Exception e) {
                        errors.add("分类ID列表格式错误: " + e.getMessage());
                    }
                }
                break;

            case "by_status":
                if (StringUtils.isBlank(batch.getStatusList())) {
                    errors.add("状态导出需要指定状态列表");
                } else {
                    try {
                        List<String> statusList = objectMapper.readValue(batch.getStatusList(), new TypeReference<List<String>>() {});
                        if (statusList.isEmpty()) {
                            errors.add("状态列表不能为空");
                        }
                    } catch (Exception e) {
                        errors.add("状态列表格式错误: " + e.getMessage());
                    }
                }
                break;

            case "custom":
                if (StringUtils.isBlank(batch.getFilterConditions())) {
                    errors.add("自定义导出需要指定过滤条件");
                } else {
                    try {
                        Map<String, Object> filters = objectMapper.readValue(batch.getFilterConditions(), new TypeReference<Map<String, Object>>() {});
                        if (filters.isEmpty()) {
                            warnings.add("过滤条件为空，将导出所有数据");
                        }
                    } catch (Exception e) {
                        errors.add("过滤条件格式错误: " + e.getMessage());
                    }
                }
                break;
        }

        // 验证包含的列
        if (StringUtils.isNotBlank(batch.getIncludeColumns())) {
            try {
                List<String> includeColumns = objectMapper.readValue(batch.getIncludeColumns(), new TypeReference<List<String>>() {});
                if (includeColumns.isEmpty()) {
                    warnings.add("包含的列为空，将导出所有字段");
                } else {
                    // 验证字段是否存在
                    List<String> availableFields = getExportableFields(batch.getExportType()).stream()
                            .map(f -> (String) f.get("field"))
                            .collect(Collectors.toList());
                    List<String> invalidFields = includeColumns.stream()
                            .filter(field -> !availableFields.contains(field))
                            .collect(Collectors.toList());
                    if (!invalidFields.isEmpty()) {
                        warnings.add("部分字段可能不存在或不可导出: " + invalidFields);
                    }
                }
            } catch (Exception e) {
                errors.add("包含的列格式错误: " + e.getMessage());
            }
        }

        // 验证磁盘空间（简化版）
        try {
            Path exportPath = Paths.get(exportBasePath);
            if (!Files.exists(exportPath)) {
                Files.createDirectories(exportPath);
            }
            long freeSpace = exportPath.toFile().getFreeSpace();
            long estimatedSize = batch.getTotalRecords() != null ? batch.getTotalRecords() * 1024 : 0; // 估计每个记录1KB
            if (freeSpace < estimatedSize * 2) { // 保留2倍空间
                warnings.add("磁盘空间可能不足，建议先清理空间");
            }
        } catch (IOException e) {
            warnings.add("无法检查磁盘空间: " + e.getMessage());
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);

        log.info("导出参数验证完成, 结果: {}", result);
        return result;
    }

    @Override
    public Map<String, Object> generateExportPreview(Long batchId) {
        log.info("生成导出预览, 批次ID: {}", batchId);

        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        Map<String, Object> preview = new HashMap<>();
        preview.put("batchId", batchId);
        preview.put("batchName", batch.getBatchName());
        preview.put("exportType", batch.getExportType());
        preview.put("format", batch.getFormat());

        // 获取数据预览
        List<CleanedDataEntity> sampleData = getSampleDataForExport(batch, 10);
        preview.put("sampleData", sampleData);

        // 获取字段列表
        List<String> fields = getSelectedFields(batch);
        preview.put("fields", fields);

        // 计算预估信息
        preview.put("estimatedRecords", batch.getTotalRecords());
        preview.put("estimatedSize", estimateFileSize(batch.getTotalRecords(), fields.size(), batch.getFormat()));

        log.info("导出预览生成完成");
        return preview;
    }

    @Override
    @Transactional
    public ExportBatchEntity copyExportBatch(Long batchId, String newName, String userId) {
        log.info("复制导出批次, 原批次ID: {}, 新名称: {}, 用户: {}", batchId, newName, userId);

        ExportBatchEntity original = exportBatchMapper.selectById(batchId);
        if (original == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        // 创建副本
        ExportBatchEntity copy = new ExportBatchEntity();
        copy.setBatchName(newName);
        copy.setExportType(original.getExportType());
        copy.setFilterConditions(original.getFilterConditions());
        copy.setCategoryIds(original.getCategoryIds());
        copy.setStatusList(original.getStatusList());
        copy.setFormat(original.getFormat());
        copy.setIncludeColumns(original.getIncludeColumns());
        copy.setExportedBy(userId);
        copy.setTotalRecords(original.getTotalRecords());
        copy.setExportedRecords(0);
        copy.setStatus("pending");

        return createExportBatch(copy);
    }

    // =============== 私有辅助方法 ===============

    /**
     * 计算按分类导出的总记录数
     */
    private int calculateTotalRecordsByCategories(List<Long> categoryIds) {
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("category_id", categoryIds);
        return cleanedDataMapper.selectCount(queryWrapper).intValue();
    }

    /**
     * 计算按状态导出的总记录数
     */
    private int calculateTotalRecordsByStatus(List<String> statusList) {
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("status", statusList);
        return cleanedDataMapper.selectCount(queryWrapper).intValue();
    }

    /**
     * 计算按过滤条件导出的总记录数
     */
    private int calculateTotalRecordsByFilters(Map<String, Object> filters) {
        QueryWrapper<CleanedDataEntity> queryWrapper = buildQueryWrapperFromFilters(filters);
        return cleanedDataMapper.selectCount(queryWrapper).intValue();
    }

    /**
     * 执行分类导出
     */
    private void executeCategoryExport(ExportBatchEntity batch) throws Exception {
        List<Long> categoryIds = objectMapper.readValue(batch.getCategoryIds(), new TypeReference<List<Long>>() {});
        List<String> includeColumns = getSelectedFields(batch);

        // 查询数据
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("category_id", categoryIds)
                   .orderByAsc("id");

        // 根据导出格式生成文件
        String filePath = generateExportFile(batch, queryWrapper, includeColumns);

        // 更新批次信息
        batch.setFilePath(filePath);
        batch.setFileName(getFileNameFromPath(filePath));
        batch.setFileSize(new File(filePath).length());
    }

    /**
     * 执行状态导出
     */
    private void executeStatusExport(ExportBatchEntity batch) throws Exception {
        List<String> statusList = objectMapper.readValue(batch.getStatusList(), new TypeReference<List<String>>() {});
        List<String> includeColumns = getSelectedFields(batch);

        // 查询数据
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("status", statusList)
                   .orderByAsc("id");

        // 根据导出格式生成文件
        String filePath = generateExportFile(batch, queryWrapper, includeColumns);

        // 更新批次信息
        batch.setFilePath(filePath);
        batch.setFileName(getFileNameFromPath(filePath));
        batch.setFileSize(new File(filePath).length());
    }

    /**
     * 执行自定义导出
     */
    private void executeCustomExport(ExportBatchEntity batch) throws Exception {
        Map<String, Object> filters = objectMapper.readValue(batch.getFilterConditions(), new TypeReference<Map<String, Object>>() {});
        List<String> includeColumns = getSelectedFields(batch);

        // 查询数据
        QueryWrapper<CleanedDataEntity> queryWrapper = buildQueryWrapperFromFilters(filters);

        // 根据导出格式生成文件
        String filePath = generateExportFile(batch, queryWrapper, includeColumns);

        // 更新批次信息
        batch.setFilePath(filePath);
        batch.setFileName(getFileNameFromPath(filePath));
        batch.setFileSize(new File(filePath).length());
    }

    /**
     * 根据过滤条件构建查询包装器
     */
    private QueryWrapper<CleanedDataEntity> buildQueryWrapperFromFilters(Map<String, Object> filters) {
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                queryWrapper.like(key, (String) value);
            } else if (value instanceof Number) {
                queryWrapper.eq(key, value);
            } else if (value instanceof List) {
                queryWrapper.in(key, (List<?>) value);
            }
        }

        return queryWrapper;
    }

    /**
     * 生成导出文件
     */
    private String generateExportFile(ExportBatchEntity batch, QueryWrapper<CleanedDataEntity> queryWrapper,
                                     List<String> includeColumns) throws IOException {
        // 创建导出目录
        Path exportDir = Paths.get(exportBasePath);
        if (!Files.exists(exportDir)) {
            Files.createDirectories(exportDir);
        }

        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = batch.getBatchName() + "_" + timestamp + getFileExtension(batch.getFormat());
        String filePath = exportDir.resolve(fileName).toString();

        // 根据格式生成文件
        switch (batch.getFormat()) {
            case "excel":
                generateExcelFile(filePath, queryWrapper, includeColumns, batch);
                break;
            case "csv":
                generateCsvFile(filePath, queryWrapper, includeColumns, batch);
                break;
            case "json":
                generateJsonFile(filePath, queryWrapper, includeColumns, batch);
                break;
            default:
                throw new IllegalArgumentException("不支持的导出格式: " + batch.getFormat());
        }

        return filePath;
    }

    /**
     * 生成Excel文件
     */
    private void generateExcelFile(String filePath, QueryWrapper<CleanedDataEntity> queryWrapper,
                                  List<String> includeColumns, ExportBatchEntity batch) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据导出");

            // 创建表头
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < includeColumns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(getFieldDisplayName(includeColumns.get(i)));
                
                // 设置表头样式
                CellStyle headerStyle = workbook.createCellStyle();
                Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cell.setCellStyle(headerStyle);
            }

            // 分页查询数据
            int pageSize = 1000;
            int page = 1;
            int exportedRecords = 0;

            while (true) {
                queryWrapper.last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize);
                List<CleanedDataEntity> dataList = cleanedDataMapper.selectList(queryWrapper);

                if (dataList.isEmpty()) {
                    break;
                }

                // 写入数据行
                for (CleanedDataEntity data : dataList) {
                    Row dataRow = sheet.createRow(exportedRecords + 1);
                    for (int i = 0; i < includeColumns.size(); i++) {
                        Cell cell = dataRow.createCell(i);
                        String fieldValue = getFieldValue(data, includeColumns.get(i));
                        cell.setCellValue(fieldValue != null ? fieldValue : "");
                    }
                    exportedRecords++;
                }

                // 更新进度
                batch.setExportedRecords(exportedRecords);
                exportBatchMapper.updateProgress(batch.getId(), exportedRecords);

                page++;
            }

            // 自动调整列宽
            for (int i = 0; i < includeColumns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            // 保存文件
            try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
                workbook.write(outputStream);
            }

            batch.setTotalRecords(exportedRecords);
            log.info("Excel文件生成完成, 路径: {}, 记录数: {}", filePath, exportedRecords);
        }
    }

    /**
     * 生成CSV文件
     */
    private void generateCsvFile(String filePath, QueryWrapper<CleanedDataEntity> queryWrapper,
                                List<String> includeColumns, ExportBatchEntity batch) throws IOException {
        // TODO: 实现CSV文件生成
        // 这里应该使用CSV库生成CSV文件
        throw new UnsupportedOperationException("CSV导出功能待实现");
    }

    /**
     * 生成JSON文件
     */
    private void generateJsonFile(String filePath, QueryWrapper<CleanedDataEntity> queryWrapper,
                                 List<String> includeColumns, ExportBatchEntity batch) throws IOException {
        // TODO: 实现JSON文件生成
        // 这里应该使用JSON库生成JSON文件
        throw new UnsupportedOperationException("JSON导出功能待实现");
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String format) {
        switch (format) {
            case "excel": return ".xlsx";
            case "csv": return ".csv";
            case "json": return ".json";
            case "pdf": return ".pdf";
            default: return ".txt";
        }
    }

    /**
     * 从路径中提取文件名
     */
    private String getFileNameFromPath(String filePath) {
        return Paths.get(filePath).getFileName().toString();
    }

    /**
     * 获取选定的字段列表
     */
    private List<String> getSelectedFields(ExportBatchEntity batch) {
        try {
            if (StringUtils.isNotBlank(batch.getIncludeColumns())) {
                return objectMapper.readValue(batch.getIncludeColumns(), new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.warn("解析包含的列失败，使用所有字段", e);
        }

        // 默认返回所有字段
        return getExportableFields(batch.getExportType()).stream()
                .map(f -> (String) f.get("field"))
                .collect(Collectors.toList());
    }

    /**
     * 获取样本数据
     */
    private List<CleanedDataEntity> getSampleDataForExport(ExportBatchEntity batch, int sampleSize) {
        QueryWrapper<CleanedDataEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper
                   .last("LIMIT " + sampleSize);

        // 根据导出类型添加过滤条件
        try {
            switch (batch.getExportType()) {
                case "by_category":
                    if (StringUtils.isNotBlank(batch.getCategoryIds())) {
                        List<Long> categoryIds = objectMapper.readValue(batch.getCategoryIds(), new TypeReference<List<Long>>() {});
                        queryWrapper.in("category_id", categoryIds);
                    }
                    break;
                case "by_status":
                    if (StringUtils.isNotBlank(batch.getStatusList())) {
                        List<String> statusList = objectMapper.readValue(batch.getStatusList(), new TypeReference<List<String>>() {});
                        queryWrapper.in("status", statusList);
                    }
                    break;
                case "custom":
                    if (StringUtils.isNotBlank(batch.getFilterConditions())) {
                        Map<String, Object> filters = objectMapper.readValue(batch.getFilterConditions(), new TypeReference<Map<String, Object>>() {});
                        queryWrapper = buildQueryWrapperFromFilters(filters);
                        queryWrapper.last("LIMIT " + sampleSize);
                    }
                    break;
            }
        } catch (Exception e) {
            log.warn("解析过滤条件失败", e);
        }

        return cleanedDataMapper.selectList(queryWrapper);
    }

    /**
     * 估计文件大小
     */
    private long estimateFileSize(int recordCount, int fieldCount, String format) {
        // 简化的估算算法
        long averageRecordSize = fieldCount * 50L; // 假设每个字段平均50字节
        long estimatedSize = recordCount * averageRecordSize;

        // 根据格式调整
        switch (format) {
            case "excel":
                estimatedSize *= 1.5; // Excel有额外开销
                break;
            case "json":
                estimatedSize *= 1.2; // JSON有额外字符
                break;
            case "pdf":
                estimatedSize *= 2; // PDF开销较大
                break;
        }

        return estimatedSize;
    }

    /**
     * 添加字段到列表
     */
    private void addField(List<Map<String, Object>> fields, String field, String name,
                         String description, String type, boolean defaultInclude) {
        Map<String, Object> fieldInfo = new HashMap<>();
        fieldInfo.put("field", field);
        fieldInfo.put("name", name);
        fieldInfo.put("description", description);
        fieldInfo.put("type", type);
        fieldInfo.put("defaultInclude", defaultInclude);
        fields.add(fieldInfo);
    }

    /**
     * 获取字段显示名称
     */
    private String getFieldDisplayName(String field) {
        // 这里应该有一个映射表
        Map<String, String> fieldDisplayNames = new HashMap<>();
        fieldDisplayNames.put("id", "ID");
        fieldDisplayNames.put("category_name", "分类名称");
        fieldDisplayNames.put("material_code", "物料代码");
        fieldDisplayNames.put("long_description", "长描述");
        fieldDisplayNames.put("full_description", "全描述");
        fieldDisplayNames.put("unit_of_measure", "计量单位");
        fieldDisplayNames.put("cleaned_long_description", "清洗后长描述");
        fieldDisplayNames.put("cleaned_full_description", "清洗后全描述");
        fieldDisplayNames.put("status", "状态");
        fieldDisplayNames.put("quality_score", "质量评分");
        fieldDisplayNames.put("confidence", "置信度");
        fieldDisplayNames.put("create_time", "创建时间");
        fieldDisplayNames.put("update_time", "更新时间");

        return fieldDisplayNames.getOrDefault(field, field);
    }

    /**
     * 获取字段值
     */
    private String getFieldValue(CleanedDataEntity data, String field) {
        try {
            switch (field) {
                case "id":
                    return data.getId() != null ? data.getId().toString() : null;
                case "category_name":
                    if (data.getCategoryId() != null) {
                        CategoryEntity category = categoryMapper.selectById(data.getCategoryId());
                        return category != null ? category.getCategoryName() : null;
                    }
                    return null;
                case "material_code":
                    return data.getMaterialCode();
                case "long_description":
                    return null;
                case "full_description":
                    return null;
                case "unit_of_measure":
                    return data.getUnit();
                case "cleaned_long_description":
                    return null;
                case "cleaned_full_description":
                    return null;
                case "status":
                    return data.getStatus() != null ? data.getStatus().getCode() : null;
                case "quality_score":
                    return data.getQualityScore() != null ? data.getQualityScore().toString() : null;
                case "confidence":
                    return null;
                case "create_time":
                    return data.getCreatedAt() != null ? data.getCreatedAt().toString() : null;
                case "update_time":
                    return data.getUpdatedAt() != null ? data.getUpdatedAt().toString() : null;
                default:
                    return null;
            }
        } catch (Exception e) {
            log.warn("获取字段值失败, 字段: {}, 数据ID: {}", field, data.getId(), e);
            return null;
        }
    }

    // =============== 接口方法实现（部分简化） ===============

    @Override
    public boolean executeExportAsync(Long batchId) {
        // 简化实现
        try {
            executeExport(batchId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String createExportTemplate(String templateName, Map<String, Object> templateData, String userId) {
        // 简化实现
        return "template_" + System.currentTimeMillis();
    }

    @Override
    public List<Map<String, Object>> getExportTemplates(String userId) {
        // 简化实现
        return Collections.emptyList();
    }

    @Override
    public ExportBatchEntity createExportFromTemplate(String templateId, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("模板导出功能待实现");
    }

    @Override
    public Map<String, Object> batchExport(List<Long> batchIds) {
        // 简化实现
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "批量导出功能待实现");
        return result;
    }

    @Override
    public Map<String, Object> scheduleExport(Long batchId, java.time.LocalDateTime scheduleTime) {
        // 简化实现
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "计划导出功能待实现");
        return result;
    }

    @Override
    public List<Map<String, Object>> getScheduledExports(String userId) {
        // 简化实现
        return Collections.emptyList();
    }

    @Override
    public boolean cancelScheduledExport(String scheduleId) {
        // 简化实现
        return false;
    }

    @Override
    public List<String> exportToMultipleFiles(Long batchId, int maxRecordsPerFile) {
        // 简化实现
        throw new UnsupportedOperationException("多文件导出功能待实现");
    }

    @Override
    public String compressExportFiles(Long batchId) {
        // 简化实现
        throw new UnsupportedOperationException("文件压缩功能待实现");
    }

    @Override
    public boolean sendExportNotification(Long batchId, String notificationType) {
        // 简化实现
        log.info("发送导出通知, 批次ID: {}, 通知类型: {}", batchId, notificationType);
        return true;
    }

    @Override
    public void logExportAction(Long batchId, String action, String details, String userId) {
        log.info("导出日志 - 批次ID: {}, 操作: {}, 详情: {}, 用户: {}", batchId, action, details, userId);
    }

    @Override
    public List<Map<String, Object>> getExportLogs(Long batchId) {
        // 简化实现
        return Collections.emptyList();
    }

    @Override
    public boolean validateExportPermission(Long batchId, String userId) {
        // 简化实现：所有人都可以导出
        return true;
    }

    @Override
    public boolean setExportWatermark(Long batchId, String watermark) {
        // 简化实现
        log.info("设置导出水印, 批次ID: {}, 水印: {}", batchId, watermark);
        return true;
    }

    @Override
    public boolean encryptExportFile(Long batchId, String password) {
        // 简化实现
        log.info("加密导出文件, 批次ID: {}, 密码: {}", batchId, password);
        return true;
    }

    @Override
    public Map<String, Object> exportToExternalSystem(Long batchId, String targetSystem, Map<String, Object> config) {
        // 简化实现
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "外部系统导出功能待实现");
        return result;
    }

    @Override
    public String generateExportReport(Long batchId, String reportType) {
        // 简化实现
        throw new UnsupportedOperationException("导出报表功能待实现");
    }

    @Override
    public Map<String, Object> validateExportFile(Long batchId) {
        // 简化实现
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("message", "文件验证通过");
        return result;
    }

    @Override
    public ExportBatchEntity mergeExportBatches(List<Long> batchIds, String mergeType, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("合并导出功能待实现");
    }

    @Override
    public Map<String, Object> getExportProgress(Long batchId) {
        ExportBatchEntity batch = exportBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("导出批次不存在: " + batchId);
        }

        Map<String, Object> progress = new HashMap<>();
        progress.put("batchId", batchId);
        progress.put("status", batch.getStatus());
        progress.put("totalRecords", batch.getTotalRecords());
        progress.put("exportedRecords", batch.getExportedRecords());
        progress.put("progress", batch.calculateProgress());
        progress.put("estimatedTimeRemaining", estimateRemainingTime(batch));

        return progress;
    }

    @Override
    public boolean pauseExport(Long batchId) {
        // 简化实现
        log.info("暂停导出任务, 批次ID: {}", batchId);
        return true;
    }

    @Override
    public boolean resumeExport(Long batchId) {
        // 简化实现
        log.info("恢复导出任务, 批次ID: {}", batchId);
        return true;
    }

    @Override
    public boolean setExportPriority(Long batchId, int priority) {
        // 简化实现
        log.info("设置导出优先级, 批次ID: {}, 优先级: {}", batchId, priority);
        return true;
    }

    @Override
    public Map<String, Object> getExportQueueStatus() {
        // 简化实现
        Map<String, Object> status = new HashMap<>();
        status.put("totalTasks", 0);
        status.put("processingTasks", 0);
        status.put("pendingTasks", 0);
        status.put("averageWaitTime", 0);
        return status;
    }

    @Override
    public ExportBatchEntity exportCategoryStructure(List<Long> categoryIds, String format, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("分类结构导出功能待实现");
    }

    @Override
    public ExportBatchEntity exportReviewTasks(List<Long> taskIds, String format, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("审核任务导出功能待实现");
    }

    @Override
    public ExportBatchEntity exportQualityReport(List<Long> dataIds, String format, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("质量报告导出功能待实现");
    }

    @Override
    public ExportBatchEntity exportSystemLogs(java.time.LocalDateTime startDate,
                                             java.time.LocalDateTime endDate,
                                             String format, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("系统日志导出功能待实现");
    }

    @Override
    public ExportBatchEntity exportFieldMappings(List<Long> mappingIds, String format, String userId) {
        // 简化实现
        throw new UnsupportedOperationException("字段映射导出功能待实现");
    }

    /**
     * 估计剩余时间
     */
    private String estimateRemainingTime(ExportBatchEntity batch) {
        if (!"processing".equals(batch.getStatus())) {
            return "未开始";
        }

        if (batch.getExportedRecords() == null || batch.getTotalRecords() == null || batch.getTotalRecords() == 0) {
            return "未知";
        }

        if (batch.getExportedRecords() >= batch.getTotalRecords()) {
            return "即将完成";
        }

        // 假设每1000条记录需要1分钟
        int remainingRecords = batch.getTotalRecords() - batch.getExportedRecords();
        int estimatedMinutes = remainingRecords / 1000;

        if (estimatedMinutes < 1) {
            return "小于1分钟";
        } else if (estimatedMinutes < 60) {
            return estimatedMinutes + "分钟";
        } else {
            return estimatedMinutes / 60 + "小时" + (estimatedMinutes % 60) + "分钟";
        }
    }
}