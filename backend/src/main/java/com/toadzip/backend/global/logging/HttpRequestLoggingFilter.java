package com.toadzip.backend.global.logging;

import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);

    private static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = RequestTraceIdResolver.resolve(request);
        long startedAt = System.nanoTime();
        MDC.put(TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            completeRequest(request, response, startedAt);
        }
    }

    private void completeRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            long startedAt
    ) {
        try {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            LOGGER.info(
                    "event=http.request.completed method={} uri={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs
            );
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
