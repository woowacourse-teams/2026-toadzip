package com.toadzip.backend.ingest.pipeline.controller;

import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class IngestExecutionLockInterceptor implements HandlerInterceptor {

    private static final String PIPELINE_PATH = "/api/admin/ingest/pipelines/";
    private static final String LEASE_ATTRIBUTE =
            IngestExecutionLockInterceptor.class.getName() + ".lease";
    private final IngestExecutionOwnershipService ownershipService;

    public IngestExecutionLockInterceptor(IngestExecutionOwnershipService ownershipService) {
        this.ownershipService = ownershipService;
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
        request.setAttribute(LEASE_ATTRIBUTE, ownershipService.acquire());
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
        if (lease instanceof IngestExecutionOwnershipService.Execution acquiredLease) {
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
