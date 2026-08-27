package com.toadzip.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestTraceIdResolver {

    private RequestTraceIdResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}
