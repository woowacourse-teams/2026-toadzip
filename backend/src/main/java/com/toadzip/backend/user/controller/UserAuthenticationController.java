package com.toadzip.backend.user.controller;

import com.toadzip.backend.notification.dto.NotificationDeviceBinding;
import com.toadzip.backend.notification.service.NotificationPushDeviceService;
import com.toadzip.backend.user.dto.CsrfTokenResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.util.WebUtils;
import com.toadzip.backend.user.dto.UserSessionResponse;
import com.toadzip.backend.user.service.SocialUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserAuthenticationController {

    private final SocialUserService socialUserService;
    private final NotificationPushDeviceService notificationDevices;

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return CsrfTokenResponse.from(csrfToken);
    }

    @GetMapping("/me")
    public UserSessionResponse currentUser(Authentication authentication, HttpServletRequest request,
            @CookieValue(name = "notification-device", required = false) UUID installation) {
        Long id = Long.valueOf(authentication.getName());
        var session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            if (session.getAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE) == null && installation != null) {
                var device = notificationDevices.current(id, installation);
                if (device.enabled()) {
                    session.setAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE,
                            new NotificationDeviceBinding(installation, id, Long.parseLong(device.bindingVersion())));
                }
            }
            return new UserSessionResponse(id, socialUserService.emailOf(id));
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
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
