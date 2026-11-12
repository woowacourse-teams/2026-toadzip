package com.toadzip.backend.user.service;

import com.toadzip.backend.admin.service.AdminAuthenticationAuditService;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.notification.dto.NotificationDeviceBinding;
import com.toadzip.backend.notification.service.NotificationPushDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/**
 * 소셜 로그인 세션을 끝낸다. 관리자 권한을 받은 소셜 계정이면 관리자 로그아웃도 감사 기록에 남긴다.
 * 사이트 로그아웃과 관리자 화면 로그아웃이 같은 절차를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class UserLogout {

    private final SocialUserService socialUserService;
    private final NotificationPushDeviceService notificationDevices;
    private final AdminAuthenticationAuditService adminAudit;

    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        String adminLoginIdentifier = socialUserService.adminLoginIdentifierOf(Long.valueOf(authentication.getName()));
        endSession(authentication, request, response);
        // 감사 기록이 실패해도 관리자가 로그아웃하지 못하는 일이 없도록 세션을 먼저 끝낸다.
        if (adminLoginIdentifier != null) {
            adminAudit.recordLogout(adminLoginIdentifier, RequestTraceIdResolver.resolve(request));
        }
    }

    private void endSession(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            return;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            var binding = (NotificationDeviceBinding) session.getAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE);
            notificationDevices.disableSessionBinding(Long.parseLong(authentication.getName()), binding);
            new SecurityContextLogoutHandler().logout(request, response, authentication);
        }
    }
}
