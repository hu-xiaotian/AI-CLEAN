package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.ReviewTaskEntity;
import com.aiclean.entity.enums.ReviewTaskType;
import com.aiclean.entity.enums.TaskPriority;
import com.aiclean.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核任务控制器
 * 负责处理审核任务的创建、分配、处理、查询等操作
 */
@RestController
@RequestMapping("/api/review")
@Tag(name = "审核任务模块", description = "人工审核任务的创建、分配、处理、查询接口")
@Slf4j
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    /**
     * 创建审核任务
     */
    @PostMapping("/tasks")
    @Operation(summary = "创建审核任务", description = "创建新的审核任务")
    public R<ReviewTaskEntity> createReviewTask(@RequestBody ReviewTaskEntity task) {
        try {
            ReviewTaskEntity created = reviewService.createReviewTask(task);
            return R.success("审核任务创建成功", created);
        } catch (Exception e) {
            log.error("创建审核任务失败", e);
            return R.error("创建审核任务失败: " + e.getMessage());
        }
    }

    /**
     * 批量创建审核任务
     */
    @PostMapping("/tasks/batch")
    @Operation(summary = "批量创建审核任务", description = "批量创建审核任务")
    public R<Integer> batchCreateReviewTasks(@RequestBody List<ReviewTaskEntity> tasks) {
        try {
            int createdCount = reviewService.batchCreateReviewTasks(tasks);
            return R.success("批量审核任务创建成功", createdCount);
        } catch (Exception e) {
            log.error("批量创建审核任务失败", e);
            return R.error("批量创建审核任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取审核任务详情
     */
    @GetMapping("/tasks/{id}")
    @Operation(summary = "获取审核任务详情", description = "获取指定审核任务的详细信息")
    public R<ReviewTaskEntity> getReviewTaskById(@PathVariable Long id) {
        try {
            ReviewTaskEntity task = reviewService.getReviewTaskById(id);
            return R.success("审核任务详情获取成功", task);
        } catch (Exception e) {
            log.error("获取审核任务详情失败", e);
            return R.error("获取审核任务详情失败: " + e.getMessage());
        }
    }

    /**
     * 获取我的待办任务
     */
    @GetMapping("/tasks/my-pending")
    @Operation(summary = "获取我的待办任务", description = "获取当前用户的待办任务")
    public R<List<ReviewTaskEntity>> getMyPendingTasks(@RequestParam("userId") String userId) {
        try {
            List<ReviewTaskEntity> tasks = reviewService.getMyPendingTasks(userId);
            return R.success("待办任务获取成功", tasks);
        } catch (Exception e) {
            log.error("获取待办任务失败", e);
            return R.error("获取待办任务失败: " + e.getMessage());
        }
    }

    /**
     * 分配任务
     */
    @PutMapping("/tasks/{id}/assign")
    @Operation(summary = "分配任务", description = "将任务分配给指定用户")
    public R<ReviewTaskEntity> assignTask(
            @PathVariable Long id,
            @RequestParam("assignee") String assignee,
            @RequestParam("assigner") String assigner) {
        try {
            ReviewTaskEntity task = reviewService.assignTask(id, assignee, assigner);
            return R.success("任务分配成功", task);
        } catch (Exception e) {
            log.error("分配任务失败", e);
            return R.error("分配任务失败: " + e.getMessage());
        }
    }

    /**
     * 开始处理任务
     */
    @PutMapping("/tasks/{id}/start")
    @Operation(summary = "开始处理任务", description = "开始处理指定任务")
    public R<ReviewTaskEntity> startTask(
            @PathVariable Long id,
            @RequestParam("userId") String userId) {
        try {
            ReviewTaskEntity task = reviewService.startTask(id, userId);
            return R.success("任务开始处理", task);
        } catch (Exception e) {
            log.error("开始处理任务失败", e);
            return R.error("开始处理任务失败: " + e.getMessage());
        }
    }

    /**
     * 完成任务
     */
    @PutMapping("/tasks/{id}/complete")
    @Operation(summary = "完成任务", description = "完成指定任务")
    public R<ReviewTaskEntity> completeTask(
            @PathVariable Long id,
            @RequestParam("userId") String userId,
            @RequestParam("resolution") String resolution,
            @RequestParam("comment") String comment) {
        try {
            ReviewTaskEntity task = reviewService.completeTask(id, userId, resolution, comment);
            return R.success("任务完成成功", task);
        } catch (Exception e) {
            log.error("完成任务失败", e);
            return R.error("完成任务失败: " + e.getMessage());
        }
    }

    /**
     * 取消任务
     */
    @PutMapping("/tasks/{id}/cancel")
    @Operation(summary = "取消任务", description = "取消指定任务")
    public R<ReviewTaskEntity> cancelTask(
            @PathVariable Long id,
            @RequestParam("userId") String userId,
            @RequestParam("reason") String reason) {
        try {
            ReviewTaskEntity task = reviewService.cancelTask(id, userId, reason);
            return R.success("任务取消成功", task);
        } catch (Exception e) {
            log.error("取消任务失败", e);
            return R.error("取消任务失败: " + e.getMessage());
        }
    }

    /**
     * 搜索任务
     */
    @GetMapping("/tasks/search")
    @Operation(summary = "搜索任务", description = "根据条件搜索任务")
    public R<List<ReviewTaskEntity>> searchTasks(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "priority", required = false) TaskPriority priority,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "taskType", required = false) ReviewTaskType taskType) {
        try {
            List<ReviewTaskEntity> tasks = reviewService.searchTasks(keyword, status, priority, assignee, taskType, null, null);
            return R.success("任务搜索成功", tasks);
        } catch (Exception e) {
            log.error("搜索任务失败", e);
            return R.error("搜索任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务统计信息
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取任务统计信息", description = "获取任务的整体统计信息")
    public R<Map<String, Object>> getTaskStatistics(
            @RequestParam(value = "userId", required = false) String userId) {
        try {
            Map<String, Object> statistics = reviewService.getTaskStatistics(userId);
            return R.success("任务统计获取成功", statistics);
        } catch (Exception e) {
            log.error("获取任务统计失败", e);
            return R.error("获取任务统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取超时任务
     */
    @GetMapping("/tasks/overdue")
    @Operation(summary = "获取超时任务", description = "获取已超时的任务")
    public R<List<ReviewTaskEntity>> getOverdueTasks() {
        try {
            List<ReviewTaskEntity> tasks = reviewService.getOverdueTasks();
            return R.success("超时任务获取成功", tasks);
        } catch (Exception e) {
            log.error("获取超时任务失败", e);
            return R.error("获取超时任务失败: " + e.getMessage());
        }
    }

    /**
     * 导出任务报告
     */
    @GetMapping("/tasks/export")
    @Operation(summary = "导出任务报告", description = "导出任务报告到文件")
    public R<String> exportTaskReport(
            @RequestParam(value = "format", defaultValue = "excel") String format) {
        try {
            Map<String, Object> filters = new HashMap<>();
            String filePath = reviewService.exportTaskReport(format, filters);
            return R.success("任务报告导出成功", filePath);
        } catch (Exception e) {
            log.error("导出任务报告失败", e);
            return R.error("导出任务报告失败: " + e.getMessage());
        }
    }
}