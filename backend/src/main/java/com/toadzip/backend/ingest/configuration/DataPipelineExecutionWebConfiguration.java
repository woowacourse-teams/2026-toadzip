package com.toadzip.backend.ingest.configuration;

import com.toadzip.backend.ingest.controller.IngestExecutionLockInterceptor;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataPipelineExecutionLock.class)
public class DataPipelineExecutionWebConfiguration implements WebMvcConfigurer {

    private final IngestExecutionLockInterceptor executionLockInterceptor;

    public DataPipelineExecutionWebConfiguration(
            DataPipelineExecutionLock executionLock
    ) {
        this.executionLockInterceptor = new IngestExecutionLockInterceptor(executionLock);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(executionLockInterceptor)
                .addPathPatterns("/api/admin/ingest/**");
    }
}
