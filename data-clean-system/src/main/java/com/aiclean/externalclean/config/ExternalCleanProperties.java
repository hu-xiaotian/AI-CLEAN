package com.aiclean.externalclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部数据清洗模块配置
 * 对应 application.yml 中 app.external-clean.* 配置项。
 * 模块独立，enabled=false 时不参与系统其他流程。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.external-clean")
public class ExternalCleanProperties {

    /** 是否启用外部清洗模块（默认关闭） */
    private boolean enabled = false;

    /** 外部清洗服务地址，例如 http://clean-service:8000 */
    private String baseUrl = "";

    /** 调用外部服务的 API Key（Bearer Token） */
    private String apiKey = "";

    /** 本系统对外暴露的回调基础地址，例如 http://java-service:8080
     *  回调地址 = {callbackBaseUrl}/api/internal/tasks/{taskId}/result */
    private String callbackBaseUrl = "";

    /** 回调接口校验令牌（请求头 X-Callback-Token），内网简单鉴权 */
    private String callbackToken = "";

    /** 调用外部服务连接/读取超时（秒） */
    private int timeoutSeconds = 5;

    /** 兜底轮询间隔（分钟） */
    private int pollIntervalMinutes = 3;

    /** 提交后超过该分钟数仍未收到回调，则标记为 callback_timeout 并依赖轮询兜底 */
    private int callbackTimeoutMinutes = 30;
}
