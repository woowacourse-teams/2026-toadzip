package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.AnnouncementViewRequest;
import com.toadzip.backend.announcement.dto.response.AnnouncementViewResponse;
import com.toadzip.backend.announcement.service.AnnouncementViewService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.toadzip.backend.privacy.controller.PrivacyCookieSupport;
import com.toadzip.backend.privacy.exception.PrivacyException;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnnouncementViewController {

    private final AnnouncementViewService service;
    private final PrivacyCookieSupport privacyCookie;

    public AnnouncementViewController(AnnouncementViewService service, PrivacyCookieSupport privacyCookie) {
        this.service = service;
        this.privacyCookie = privacyCookie;
    }

    @PostMapping("/{announcementId}/views")
    public ResponseEntity<ApiResponse<AnnouncementViewResponse>> recordView(
            @PathVariable long announcementId, @Valid @RequestBody AnnouncementViewRequest request,
            Authentication authentication, HttpServletRequest servletRequest
    ) {
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
        long count = service.recordView(announcementId, UUID.fromString(request.viewerId()), userId,
                privacyCookie.readToken(servletRequest));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(new AnnouncementViewResponse(count)));
    }
}
