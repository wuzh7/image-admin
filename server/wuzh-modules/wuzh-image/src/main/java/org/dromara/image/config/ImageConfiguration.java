package org.dromara.image.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ImageProperties.class)
public class ImageConfiguration {

    @Bean("imageAdmissionScheduler")
    public ThreadPoolTaskScheduler imageAdmissionScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-admission-");
        return scheduler;
    }

    @Bean("imageCleanupScheduler")
    public ThreadPoolTaskScheduler imageCleanupScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-cleanup-");
        return scheduler;
    }

    @Bean("imageTaskExecutor")
    public ThreadPoolTaskExecutor imageTaskExecutor(ImageProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workerCount = Math.max(1, properties.getTaskWorkers());
        executor.setCorePoolSize(workerCount);
        executor.setMaxPoolSize(workerCount);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("image-dedup-");
        executor.initialize();
        return executor;
    }
}
