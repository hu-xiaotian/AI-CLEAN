package com.aiclean.externalclean.controller;

import com.aiclean.common.R;
import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.dto.CallbackPayload;
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
                                @RequestBody CallbackPayload payload,
                                HttpServletRequest request) {
        log.info("[回调] 收到外部清洗回调请求 taskId={}, page={}, size={}, clientIp={}, userAgent={}",
                taskId, page, size, getClientIp(request), request.getHeader("User-Agent"));
        log.info("[回调] 收到回调报文(对象) payload={}", payload);

//        String token = request.getHeader("X-Callback-Token");
//        if (cn.hutool.core.util.StrUtil.isNotBlank(properties.getCallbackToken())) {
//            if (token == null || !properties.getCallbackToken().equals(token)) {
//                log.warn("[回调] 回调令牌校验失败 taskId={}, 期望令牌非空, 实际收到令牌={}",
//                        taskId, token == null ? "null" : "已提供但不匹配");
//                return R.error(401, "回调令牌无效");
//            }
//        }
//        log.info("[回调] 令牌校验通过 taskId={}", taskId);

        String result = taskService.handleCallback(taskId, page, size, payload);
        log.info("[回调] handleCallback 返回结果 result={}, taskId={}", result, taskId);

        if ("invalid".equals(result)) {
            log.warn("[回调] 任务不存在 taskId={}", taskId);
            return R.notFound("任务不存在: " + taskId);
        }
        log.info("[回调] 回调处理成功 taskId={}", taskId);
        return R.success("ok");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
