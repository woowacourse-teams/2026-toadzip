package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class IngestExecutionLockInterceptor implements HandlerInterceptor {

    private static final String PIPELINE_PATH = "/api/admin/ingest/pipelines/";
    private static final String LEASE_ATTRIBUTE =
            IngestExecutionLockInterceptor.class.getName() + ".lease";
    private static final String ALREADY_RUNNING_MESSAGE =
            "다른 데이터 수집·정제 작업이 이미 실행 중입니다.";

    private final DataPipelineExecutionLock executionLock;

    public IngestExecutionLockInterceptor(DataPipelineExecutionLock executionLock) {
        this.executionLock = executionLock;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (shouldBypass(request)) {
            return true;
        }
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire()
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        request.setAttribute(LEASE_ATTRIBUTE, lease);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        Object lease = request.getAttribute(LEASE_ATTRIBUTE);
        if (lease instanceof DataPipelineExecutionLock.Lease acquiredLease) {
            acquiredLease.close();
        }
    }

    private boolean shouldBypass(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        return request.getRequestURI().startsWith(PIPELINE_PATH);
    }
}
