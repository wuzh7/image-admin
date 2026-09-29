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

    @Bean("imageDailyScanScheduler")
    public ThreadPoolTaskScheduler imageDailyScanScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-daily-scan-");
        return scheduler;
    }

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

    @Bean("imageInfringementScheduler")
    public ThreadPoolTaskScheduler imageInfringementScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-infringement-scheduler-");
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

    @Bean("imageRelayInfringementExecutor")
    public ThreadPoolTaskExecutor imageRelayInfringementExecutor(ImageProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = Math.max(1, properties.getInfringement().getRelayWorkers());
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("image-infringement-relay-");
        executor.initialize();
        return executor;
    }
}
