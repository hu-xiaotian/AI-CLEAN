package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.ExportBatchEntity;
import com.aiclean.service.ExportService;
import com.aiclean.vo.ExportRequestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导出控制器
 * 负责处理数据导出请求、导出批次管理、导出进度跟踪等操作
 */
@RestController
@RequestMapping("/api/export")
@Tag(name = "数据导出模块", description = "数据导出、导出批次管理、导出进度跟踪接口")
@Slf4j
public class ExportController {

    @Autowired
    private ExportService exportService;

    /**
     * 按分类导出数据
     */
    @PostMapping("/by-categories")
    @Operation(summary = "按分类导出数据", description = "根据分类ID列表导出数据")
    public R<ExportBatchEntity> exportByCategories(@RequestBody ExportRequestVO request) {
        try {
            if (request == null || !request.isValid()) {
                return R.error("导出请求参数无效");
            }
            
            ExportBatchEntity batch = exportService.exportByCategories(
                    request.getCategoryIds(),
                    request.getFormat(),
                    request.getIncludeColumns(),
                    request.getUserId());
            return R.success("导出任务创建成功", batch);
        } catch (Exception e) {
            log.error("创建导出任务失败", e);
            return R.error("创建导出任务失败: " + e.getMessage());
        }
    }

    /**
     * 执行导出任务
     */
    @PostMapping("/execute/{batchId}")
    @Operation(summary = "执行导出任务", description = "执行指定的导出任务")
    public R<ExportBatchEntity> executeExport(@PathVariable Long batchId) {
        try {
            ExportBatchEntity batch = exportService.executeExport(batchId);
            return R.success("导出任务执行成功", batch);
        } catch (Exception e) {
            log.error("执行导出任务失败", e);
            return R.error("执行导出任务失败: " + e.getMessage());
        }
    }

    /**
     * 异步执行导出任务
     */
    @PostMapping("/execute-async/{batchId}")
    @Operation(summary = "异步执行导出任务", description = "异步执行指定的导出任务")
    public R<Boolean> executeExportAsync(@PathVariable Long batchId) {
        try {
            boolean started = exportService.executeExportAsync(batchId);
            return R.success("异步导出任务启动成功", started);
        } catch (Exception e) {
            log.error("启动异步导出任务失败", e);
            return R.error("启动异步导出任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取我的导出历史
     */
    @GetMapping("/my-history")
    @Operation(summary = "获取我的导出历史", description = "获取当前用户的导出历史")
    public R<List<ExportBatchEntity>> getMyExportHistory(
            @RequestParam(value = "userId") String userId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        try {
            List<ExportBatchEntity> history = exportService.getMyExportHistory(userId, page, size);
            return R.success("导出历史获取成功", history);
        } catch (Exception e) {
            log.error("获取导出历史失败", e);
            return R.error("获取导出历史失败: " + e.getMessage());
        }
    }

    /**
     * 获取导出批次详情
     */
    @GetMapping("/batches/{batchId}")
    @Operation(summary = "获取导出批次详情", description = "获取指定导出批次的详细信息")
    public R<ExportBatchEntity> getExportBatchById(@PathVariable Long batchId) {
        try {
            ExportBatchEntity batch = exportService.getExportBatchById(batchId);
            return R.success("导出批次详情获取成功", batch);
        } catch (Exception e) {
            log.error("获取导出批次详情失败", e);
            return R.error("获取导出批次详情失败: " + e.getMessage());
        }
    }

    /**
     * 获取导出进度
     */
    @GetMapping("/progress/{batchId}")
    @Operation(summary = "获取导出进度", description = "获取指定导出任务的进度信息")
    public R<Map<String, Object>> getExportProgress(@PathVariable Long batchId) {
        try {
            Map<String, Object> progress = exportService.getExportProgress(batchId);
            return R.success("导出进度获取成功", progress);
        } catch (Exception e) {
            log.error("获取导出进度失败", e);
            return R.error("获取导出进度失败: " + e.getMessage());
        }
    }

    /**
     * 下载导出文件
     */
    @GetMapping("/download/{batchId}")
    @Operation(summary = "下载导出文件", description = "下载指定批次的导出文件")
    public void downloadExportFile(@PathVariable Long batchId) {
        try {
            // 这里需要实现文件下载逻辑
            String filePath = exportService.downloadExportFile(batchId);
            // 实际实现中这里会返回文件流
            log.info("下载文件: {}", filePath);
        } catch (Exception e) {
            log.error("下载导出文件失败", e);
            throw new RuntimeException("下载导出文件失败: " + e.getMessage());
        }
    }

    /**
     * 取消导出任务
     */
    @PutMapping("/cancel/{batchId}")
    @Operation(summary = "取消导出任务", description = "取消指定的导出任务")
    public R<Boolean> cancelExport(@PathVariable Long batchId) {
        try {
            boolean cancelled = exportService.cancelExport(batchId);
            return R.success("导出任务取消成功", cancelled);
        } catch (Exception e) {
            log.error("取消导出任务失败", e);
            return R.error("取消导出任务失败: " + e.getMessage());
        }
    }

    /**
     * 删除导出批次
     */
    @DeleteMapping("/batches/{batchId}")
    @Operation(summary = "删除导出批次", description = "删除指定的导出批次")
    public R<Boolean> deleteExportBatch(@PathVariable Long batchId) {
        try {
            exportService.deleteExportBatch(batchId);
            return R.success("导出批次删除成功", true);
        } catch (Exception e) {
            log.error("删除导出批次失败", e);
            return R.error("删除导出批次失败: " + e.getMessage());
        }
    }

    /**
     * 获取导出统计信息
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取导出统计信息", description = "获取导出相关的统计信息")
    public R<Map<String, Object>> getExportStatistics(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "days", defaultValue = "30") Integer days) {
        try {
            Map<String, Object> statistics = exportService.getExportStatistics(userId, days);
            return R.success("导出统计获取成功", statistics);
        } catch (Exception e) {
            log.error("获取导出统计失败", e);
            return R.error("获取导出统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取支持的导出格式
     */
    @GetMapping("/formats")
    @Operation(summary = "获取支持的导出格式", description = "获取系统支持的导出文件格式")
    public R<List<Map<String, Object>>> getSupportedFormats() {
        try {
            List<Map<String, Object>> formats = exportService.getSupportedFormats();
            return R.success("导出格式列表获取成功", formats);
        } catch (Exception e) {
            log.error("获取导出格式列表失败", e);
            return R.error("获取导出格式列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取导出队列状态
     */
    @GetMapping("/queue-status")
    @Operation(summary = "获取导出任务队列状态", description = "获取导出任务队列的状态信息")
    public R<Map<String, Object>> getExportQueueStatus() {
        try {
            Map<String, Object> status = exportService.getExportQueueStatus();
            return R.success("导出队列状态获取成功", status);
        } catch (Exception e) {
            log.error("获取导出队列状态失败", e);
            return R.error("获取导出队列状态失败: " + e.getMessage());
        }
    }
}