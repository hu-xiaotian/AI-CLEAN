package com.aiclean.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与并行执行器配置。
 * - cleaningExecutor：供 Sharding Agent 并行清洗分片使用。
 * - taskExecutor：Spring @Async 默认异步执行器（startCleaning / startAiExtract / aiClassifyCheckAsync 等）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 并行清洗分片线程池（供 ShardingAgent 使用）
     */
    @Bean(name = "cleaningExecutor")
    public ThreadPoolTaskExecutor cleaningExecutor(
            @Value("${app.data-cleaning.sharding.parallelism:4}") int parallelism) {
        int core = Math.max(2, parallelism);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(core * 2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("cleaning-shard-");
        // 队列满时由调用线程执行，避免任务丢失（CallerRunsPolicy）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * @Async 默认线程池（Spring 约定：名为 taskExecutor 的 Bean 作为默认异步执行器）。
     * 用于 startCleaning / startAiExtract / aiClassifyCheckAsync 等 @Async 方法，
     * 避免使用 SimpleAsyncTaskExecutor（每次创建新线程，无池化限制）。
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
