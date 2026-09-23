package com.toadzip.backend.user.service;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);

    private final SocialUserService socialUserService;
    private final SecurityContextRepository securityContextRepository;
    private final SocialLoginFailureHandler failureHandler;

    @Value("${app.user.oauth.success-url}")
    private String successUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        Long userId;
        try {
            OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
            Object kakaoId = oauth.getPrincipal().getAttributes().get("id");
            String subject = kakaoId == null ? null : kakaoId.toString();
            if ("google".equals(oauth.getAuthorizedClientRegistrationId())) {
                subject = oauth.getPrincipal().getAttribute("sub");
            }
            userId = socialUserService.findOrCreate(oauth.getAuthorizedClientRegistrationId(), subject);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=user.login.failed phase=session reason={}", exception.getClass().getSimpleName());
            failureHandler.failAfterProviderAuthentication(response);
            return;
        }
        Authentication userAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                userId.toString(), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        request.getSession();
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(userAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        response.sendRedirect(successUrl);
    }
}
