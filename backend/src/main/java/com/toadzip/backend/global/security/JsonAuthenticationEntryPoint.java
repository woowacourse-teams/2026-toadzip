package com.toadzip.backend.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter securityErrorResponseWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        securityErrorResponseWriter.write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "관리자 로그인이 필요합니다."
        );
    }
}
