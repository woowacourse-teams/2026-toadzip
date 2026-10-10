package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.service.NotificationInterestService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.toadzip.backend.privacy.controller.PrivacyCookieSupport;
import com.toadzip.backend.privacy.exception.PrivacyException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-interest-events")
@RequiredArgsConstructor
public class NotificationInterestController {

    private final NotificationInterestService service;
    private final PrivacyCookieSupport privacyCookie;

    @PostMapping
    public NotificationInterestResponse record(
            @Valid @RequestBody NotificationInterestRequest request, Authentication authentication,
            HttpServletRequest servletRequest) {
        Long userId = null;
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority()))) {
            userId = Long.valueOf(authentication.getName());
        }
        if (authentication != null && userId == null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> !"ROLE_ANONYMOUS".equals(authority.getAuthority()))) {
            throw new PrivacyException("FORBIDDEN", "회원 또는 비회원 요청만 허용합니다.");
        }
        return service.record(request, userId, privacyCookie.readToken(servletRequest));
    }
}
