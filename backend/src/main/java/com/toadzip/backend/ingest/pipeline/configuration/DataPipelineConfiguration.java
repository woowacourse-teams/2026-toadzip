package com.toadzip.backend.ingest.pipeline.configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(DataPipelineSchedulerProperties.class)
public class DataPipelineConfiguration {

    @Bean(name = "dataPipelineExecutor")
    public Executor dataPipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("data-pipeline-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "dataPipelineHeartbeatExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService dataPipelineHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("data-pipeline-heartbeat-", 0).factory()
        );
    }

    @Bean(name = "dataPipelineScheduleTaskScheduler")
    public ThreadPoolTaskScheduler dataPipelineScheduleTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("data-pipeline-scheduler-");
        return scheduler;
    }
}
