package com.toadzip.backend.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter securityErrorResponseWriter;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        securityErrorResponseWriter.write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                messageFor(request, accessDeniedException)
        );
    }

    private String messageFor(HttpServletRequest request, AccessDeniedException exception) {
        if (exception instanceof CsrfException) {
            return "요청 보안 토큰이 유효하지 않습니다.";
        }
        if (request.getRequestURI().startsWith("/api/auth/")) {
            return "사용자 권한이 필요합니다.";
        }
        return "관리자 권한이 필요합니다.";
    }
}
