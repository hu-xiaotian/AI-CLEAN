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

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

/**
 * 外部清洗任务管理接口（面向前端）
 */
@Slf4j
@RestController
@Tag(name = "外部数据清洗模块", description = "调用外部清洗服务、接收回调、结果采纳与修正")
public class ExternalCleanTaskController {

    private final ExternalCleanTaskService taskService;

    public ExternalCleanTaskController(ExternalCleanTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/api/external-clean/tasks")
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

    @GetMapping("/api/external-clean/tasks")
    @Operation(summary = "任务分页列表", description = "按状态筛选分页查询")
    public R<IPage<ExternalCleanTaskEntity>> listTasks(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String sortField,
                                                       @RequestParam(required = false) String sortOrder) {
        return R.success(taskService.listTasks(page, size, status, sortField, sortOrder));
    }

    @GetMapping("/api/external-clean/tasks/{taskId}")
    @Operation(summary = "任务详情", description = "返回任务状态与统计")
    public R<ExternalCleanTaskEntity> getTask(@PathVariable String taskId) {
        ExternalCleanTaskEntity task = taskService.getTask(taskId);
        if (task == null) return R.notFound("任务不存在");
        return R.success(task);
    }

    @PostMapping("/api/external-clean/tasks/{taskId}/progress")
    @Operation(summary = "主动查询外部任务进展", description = "调用外部 /api/v1/clean/{task_id} 获取进度并回写数据库，供前端定时刷新")
    public R<ExternalCleanTaskEntity> refreshProgress(@PathVariable String taskId) {
        try {
            ExternalCleanTaskEntity task = taskService.queryAndUpdateProgress(taskId);
            if (task == null) return R.notFound("任务不存在");
            return R.success(task);
        } catch (Exception e) {
            return R.error("查询进展失败: " + e.getMessage());
        }
    }

    @GetMapping("/api/external-clean/tasks/{taskId}/rows")
    @Operation(summary = "任务结果行分页", description = "可按 needsReview=1 过滤待复核行")
    public R<IPage<ExternalCleanTaskRowEntity>> listRows(@PathVariable String taskId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) Integer needsReview) {
        return R.success(taskService.listRows(taskId, page, size, needsReview));
    }

    @PostMapping("/api/external-clean/tasks/{taskId}/cancel")
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

    @PostMapping("/api/external-clean/tasks/{taskId}/retry")
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

    @PostMapping("/api/external-clean/tasks/{taskId}/rows/{rowIndex}/adopt")
    @Operation(summary = "采纳单行结果")
    public R<Void> adoptRow(@PathVariable String taskId, @PathVariable int rowIndex) {
        try {
            taskService.adoptRow(taskId, rowIndex);
            return R.success("已采纳");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/api/external-clean/tasks/{taskId}/adopt-all")
    @Operation(summary = "采纳全部已完成行")
    public R<Void> adoptAll(@PathVariable String taskId) {
        try {
            taskService.adoptAll(taskId);
            return R.success("已采纳全部");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/api/external-clean/tasks/{taskId}/rows/{rowIndex}/reject")
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

    @PostMapping("/api/external-clean/tasks/{taskId}/rows/{rowIndex}/correct")
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

    @GetMapping("/api/external-clean/tasks/{taskId}/export")
    @Operation(summary = "按分类导出结果 Excel", description = "按分类分 Sheet，每个 Sheet 表头为 extractedAttrsJson 属性列（缺失属性补空列），内容为扁平化列表")
    public void exportTaskRows(@PathVariable String taskId, HttpServletResponse response) {
        try {
            byte[] data = taskService.exportRowsByCategory(taskId);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "external_clean_result_" + taskId + "_" + timestamp + ".xlsx";
            String encodedName = URLEncoder.encode(fileName, "UTF-8").replaceAll("\\+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
            response.setContentLength(data.length);
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
        } catch (IOException e) {
            log.error("导出外部清洗结果失败", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"" + e.getMessage() + "\"}");
            } catch (IOException ignored) { }
        } catch (Exception e) {
            log.error("导出外部清洗结果失败", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/api/external-clean/tasks/{taskId}/rows/{rowIndex}/fill-missing")
    @Operation(summary = "填充缺失属性", description = "将手工填入的缺失属性合并进 extractedAttrsJson，并从 missingAttrsJson 移除已填充项")
    public R<Void> fillMissing(@PathVariable String taskId, @PathVariable int rowIndex,
                              @RequestBody Map<String, String> filled) {
        try {
            taskService.fillMissing(taskId, rowIndex, filled);
            return R.success("已填充缺失属性");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @DeleteMapping("/api/external-clean/tasks/{taskId}")
    @Operation(summary = "删除任务及关联记录", description = "删除任务及其关联的结果行、回调日志。仅允许终态或待处理任务删除，进行中任务请先取消")
    public R<Void> deleteTask(@PathVariable String taskId) {
        try {
            taskService.deleteTask(taskId);
            return R.success("已删除");
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return R.error(e.getMessage());
        }
    }

    @PostMapping("/api/v1/clean/{task_id}/pause")
    @Operation(summary = "暂停清洗任务", description = "调用外部清洗服务暂停接口，与提交异步清洗接口地址一致")
    public R<Void> pauseTask(@PathVariable("task_id") String taskId) {
        try {
            taskService.pauseTask(taskId);
            return R.success("已暂停");
        } catch (IllegalStateException e) {
            return R.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }

    @PostMapping("/api/v1/clean/{task_id}/resume")
    @Operation(summary = "继续清洗任务", description = "调用外部清洗服务继续接口，与提交异步清洗接口地址一致")
    public R<Void> resumeTask(@PathVariable("task_id") String taskId) {
        try {
            taskService.resumeTask(taskId);
            return R.success("已继续");
        } catch (IllegalStateException e) {
            return R.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        }
    }
}
