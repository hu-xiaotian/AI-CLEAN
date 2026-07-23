package com.aiclean.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与并行执行器配置。
 * 提供名为 cleaningExecutor 的线程池，供 Sharding Agent 并行清洗分片使用。
 * 注意：@Async（startCleaning / aiClassifyCheckAsync 等）默认仍走 Spring 的
 * SimpleAsyncTaskExecutor，不受此 bean 影响（本 bean 名为 cleaningExecutor 而非 taskExecutor）。
 */
@Configuration
public class AsyncConfig {

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
}
