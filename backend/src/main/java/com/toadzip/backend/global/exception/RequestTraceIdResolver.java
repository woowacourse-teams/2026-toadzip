package com.toadzip.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestTraceIdResolver {

    private static final String TRACE_ID_ATTRIBUTE = RequestTraceIdResolver.class.getName() + ".traceId";

    private RequestTraceIdResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        Object savedTraceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (savedTraceId instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String traceId = createTraceId(request);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private static String createTraceId(HttpServletRequest request) {
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }
}
