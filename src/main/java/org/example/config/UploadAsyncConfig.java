package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background executor for long-running upload ingest (Excel parse + MongoDB).
 * Exposed as {@link ThreadPoolTaskExecutor} so jobs can be submitted with a {@link java.util.concurrent.Future} for timeout/watchdog cancellation.
 */
@Configuration
public class UploadAsyncConfig {

    @Bean(name = "uploadTaskExecutor")
    public ThreadPoolTaskExecutor uploadTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("upload-job-");
        executor.initialize();
        return executor;
    }
}
