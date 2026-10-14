package com.toadzip.backend.user.service;

import com.toadzip.backend.notification.dto.NotificationDeviceBinding;
import com.toadzip.backend.notification.service.NotificationPushDeviceService;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestResolver;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.util.WebUtils;
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
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);

    private final SocialUserService socialUserService;
    private final SecurityContextRepository securityContextRepository;
    private final SocialLoginFailureHandler failureHandler;
    private final NotificationPushDeviceService notificationDevices;

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
            String subject = null;
            if (kakaoId != null) {
                subject = kakaoId.toString();
            }
            if ("google".equals(oauth.getAuthorizedClientRegistrationId())) {
                subject = oauth.getPrincipal().getAttribute("sub");
            }
            Object emailAttribute = oauth.getPrincipal().getAttribute("email");
            String email = null;
            if (emailAttribute instanceof String value) {
                email = value;
            }
            if ("kakao".equals(oauth.getAuthorizedClientRegistrationId())) {
                Object account = oauth.getPrincipal().getAttribute("kakao_account");
                if (account instanceof Map<?, ?> accountDetails
                        && accountDetails.get("email") instanceof String value) {
                    email = value;
                }
            }
            SocialAuthorizationContext authorization = (SocialAuthorizationContext) request.getAttribute(
                    SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE);
            request.removeAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE);
            userId = socialUserService.findOrCreate(oauth.getAuthorizedClientRegistrationId(), subject,
                    email, authorization);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=user.login.failed phase=session reason={}", exception.getClass().getSimpleName());
            failureHandler.failAfterProviderAuthentication(response);
            return;
        }
        Authentication userAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                userId.toString(), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        request.getSession();
        request.changeSessionId();
        captureNotificationBinding(request, userId);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(userAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        response.sendRedirect(successRedirectUrl());
    }

    private void captureNotificationBinding(HttpServletRequest request, long userId) {
        var session = request.getSession();
        session.removeAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE);
        var cookie = WebUtils.getCookie(request, "notification-device");
        if (cookie == null) {
            return;
        }
        UUID installation;
        try {
            installation = UUID.fromString(cookie.getValue());
        } catch (IllegalArgumentException exception) {
            return;
        }
        var device = notificationDevices.current(userId, installation);
        if (device.enabled()) {
            session.setAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE,
                    new NotificationDeviceBinding(installation, userId, Long.parseLong(device.bindingVersion())));
        }
    }

    private String successRedirectUrl() {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(successUrl);
        String path = url.build().getPath();
        if (path == null || path.isEmpty() || "/".equals(path) || "/login".equals(path)) {
            return url.replacePath("/explore").build().toUriString();
        }
        return successUrl;
    }
}
