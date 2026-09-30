package com.toadzip.backend.user.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginFailureHandler.class);

    @Value("${app.user.oauth.failure-url}")
    private String failureUrl;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception
    ) throws IOException {
        LOGGER.warn("event=user.login.failed phase=provider reason={}", exception.getClass().getSimpleName());
        response.sendRedirect(failureUrl);
    }

    public void failAfterProviderAuthentication(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendRedirect(failureUrl);
    }
}
