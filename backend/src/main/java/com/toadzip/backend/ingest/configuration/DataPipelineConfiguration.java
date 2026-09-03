package com.toadzip.backend.ingest.configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
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
}
