package com.toadzip.backend.ingest.pipeline.configuration;

import com.toadzip.backend.ingest.pipeline.controller.IngestExecutionLockInterceptor;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataPipelineExecutionLock.class)
public class DataPipelineExecutionWebConfiguration implements WebMvcConfigurer {

    private final IngestExecutionLockInterceptor executionLockInterceptor;

    public DataPipelineExecutionWebConfiguration(
            IngestExecutionOwnershipService ownershipService
    ) {
        this.executionLockInterceptor = new IngestExecutionLockInterceptor(ownershipService);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(executionLockInterceptor)
                .addPathPatterns("/api/admin/ingest/**");
    }
}
