package com.aiclean.externalclean.scheduler;

import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.service.ExternalCleanTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 外部清洗任务兜底调度
 * 应对回调 3 次重试全部失败的场景（api-design.md 5.2）：
 * 对 processing 且超时未收到回调的任务，主动轮询外部服务拉取结果。
 */
@Slf4j
@Component
@EnableScheduling
public class ExternalCleanScheduler {

    private final ExternalCleanTaskService taskService;
    private final ExternalCleanProperties properties;

    public ExternalCleanScheduler(ExternalCleanTaskService taskService, ExternalCleanProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{@externalCleanProperties.pollIntervalMinutes * 60000}")
    public void pollFallback() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            taskService.pollFallback();
        } catch (Exception e) {
            log.warn("外部清洗兜底轮询异常: {}", e.getMessage());
        }
    }
}
