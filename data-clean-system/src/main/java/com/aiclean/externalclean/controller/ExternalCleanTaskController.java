package com.aiclean.externalclean.controller;

import com.aiclean.common.R;
import com.aiclean.externalclean.dto.SubmitExternalCleanTaskRequest;
import com.aiclean.externalclean.dto.TaskRowCorrectRequest;
import com.aiclean.externalclean.entity.ExternalCleanTaskEntity;
import com.aiclean.externalclean.entity.ExternalCleanTaskRowEntity;
import com.aiclean.externalclean.service.ExternalCleanTaskService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 外部清洗任务管理接口（面向前端）
 */
@Slf4j
@RestController
@RequestMapping("/api/external-clean")
@Tag(name = "外部数据清洗模块", description = "调用外部清洗服务、接收回调、结果采纳与修正")
public class ExternalCleanTaskController {

    private final ExternalCleanTaskService taskService;

    public ExternalCleanTaskController(ExternalCleanTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/tasks")
    @Operation(summary = "提交外部清洗任务", description = "选定已导入文件（及可选行），提交外部清洗服务")
    public R<ExternalCleanTaskEntity> submitTask(@RequestBody SubmitExternalCleanTaskRequest request) {
        try {
            return R.success(taskService.submitTask(request));
        } catch (IllegalStateException e) {
            return R.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return R.badRequest(e.getMessage());
        } catch (Exception e) {
            return R.error("提交失败: " + e.getMessage());
        }
    }

    @GetMapping("/tasks")
    @Operation(summary = "任务分页列表", description = "按状态筛选分页查询")
    public R<IPage<ExternalCleanTaskEntity>> listTasks(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       @RequestParam(required = false) String status) {
        return R.success(taskService.listTasks(page, size, status));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "任务详情", description = "返回任务状态与统计")
    public R<ExternalCleanTaskEntity> getTask(@PathVariable String taskId) {
        ExternalCleanTaskEntity task = taskService.getTask(taskId);
        if (task == null) return R.notFound("任务不存在");
        return R.success(task);
    }

    @GetMapping("/tasks/{taskId}/rows")
    @Operation(summary = "任务结果行分页", description = "可按 needsReview=1 过滤待复核行")
    public R<IPage<ExternalCleanTaskRowEntity>> listRows(@PathVariable String taskId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) Integer needsReview) {
        return R.success(taskService.listRows(taskId, page, size, needsReview));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @Operation(summary = "取消任务")
    public R<Void> cancelTask(@PathVariable String taskId) {
        try {
            taskService.cancelTask(taskId);
            return R.success("已取消");
        } catch (IllegalStateException e) {
            return R.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "失败任务重试")
    public R<ExternalCleanTaskEntity> retryTask(@PathVariable String taskId) {
        try {
            return R.success(taskService.retryTask(taskId));
        } catch (IllegalStateException e) {
            return R.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/rows/{rowIndex}/adopt")
    @Operation(summary = "采纳单行结果")
    public R<Void> adoptRow(@PathVariable String taskId, @PathVariable int rowIndex) {
        try {
            taskService.adoptRow(taskId, rowIndex);
            return R.success("已采纳");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/adopt-all")
    @Operation(summary = "采纳全部已完成行")
    public R<Void> adoptAll(@PathVariable String taskId) {
        try {
            taskService.adoptAll(taskId);
            return R.success("已采纳全部");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/rows/{rowIndex}/reject")
    @Operation(summary = "驳回单行")
    public R<Void> rejectRow(@PathVariable String taskId, @PathVariable int rowIndex,
                             @RequestParam(required = false) String comment) {
        try {
            taskService.rejectRow(taskId, rowIndex, comment);
            return R.success("已驳回");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/rows/{rowIndex}/correct")
    @Operation(summary = "修正单行结果")
    public R<Void> correctRow(@PathVariable String taskId, @PathVariable int rowIndex,
                              @RequestBody TaskRowCorrectRequest request) {
        try {
            taskService.correctRow(taskId, rowIndex, request);
            return R.success("已修正");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }
}
