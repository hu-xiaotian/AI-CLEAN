package com.aiclean.externalclean.controller;

import com.aiclean.common.R;
import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.service.ExternalCleanTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 外部清洗服务回调接收端点
 * 路径与 api-design.md 第5节一致：POST /api/internal/tasks/{taskId}/result
 * 该端点不走 JWT 鉴权（已在 WebConfig 排除 /api/internal/**），仅以 X-Callback-Token 做内网简单校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/tasks")
public class ExternalCleanCallbackController {

    private final ExternalCleanTaskService taskService;
    private final ExternalCleanProperties properties;

    public ExternalCleanCallbackController(ExternalCleanTaskService taskService, ExternalCleanProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @PostMapping("/{taskId}/result")
    public R<?> receiveCallback(@PathVariable String taskId,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size,
                                @RequestBody String rawBody,
                                HttpServletRequest request) {
        String token = request.getHeader("X-Callback-Token");
        if (cn.hutool.core.util.StrUtil.isNotBlank(properties.getCallbackToken())) {
            if (token == null || !properties.getCallbackToken().equals(token)) {
                return R.error(401, "回调令牌无效");
            }
        }
        String result = taskService.handleCallback(taskId, page, size, rawBody);
        if ("invalid".equals(result)) {
            return R.notFound("任务不存在: " + taskId);
        }
        return R.success("ok");
    }
}
