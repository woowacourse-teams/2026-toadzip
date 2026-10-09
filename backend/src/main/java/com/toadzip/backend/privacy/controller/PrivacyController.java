package com.toadzip.backend.privacy.controller;

import com.toadzip.backend.privacy.dto.AnalyticsContextResponse;
import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.privacy.dto.PrivacyChoiceResponse;
import com.toadzip.backend.privacy.dto.PrivacyNoticeResponse;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.service.AnalyticsConsentService;
import com.toadzip.backend.privacy.service.PrivacyNoticeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final AnalyticsConsentService consents;
    private final PrivacyNoticeService notices;
    private final PrivacyCookieSupport cookies;

    @GetMapping("/notices/current")
    public ResponseEntity<Map<String, List<PrivacyNoticeResponse>>> notices() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(Map.of("documents", notices.currentDocuments()));
    }

    @GetMapping("/notices/{key}/{version}")
    public ResponseEntity<PrivacyNoticeResponse> notice(@PathVariable String key, @PathVariable String version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(notices.find(key, version));
    }

    @GetMapping("/analytics-context")
    public ResponseEntity<AnalyticsContextResponse> context(Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(consents.context(userId(authentication), cookies.readToken(request)));
    }

    @PostMapping("/analytics/guest-context")
    public ResponseEntity<AnalyticsContextResponse> prepareGuest(Authentication authentication,
            HttpServletRequest request) {
        requireGuest(authentication);
        var prepared = consents.prepareGuest(cookies.readToken(request));
        AnalyticsContextResponse context = prepared.context();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.header(prepared.token(), context.checkedAt(),
                        context.consent().expiresAt())).body(context);
    }

    @PostMapping("/analytics/guest")
    public ResponseEntity<PrivacyChoiceResponse> chooseGuest(Authentication authentication, HttpServletRequest request,
            @RequestBody PrivacyChoiceRequest choice) {
        requireGuest(authentication);
        String token = cookies.readToken(request);
        PrivacyChoiceResponse response = consents.chooseGuest(token, choice);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.header(token, response.current().checkedAt(),
                        response.current().consent().expiresAt())).body(response);
    }

    @PostMapping("/analytics/me")
    public ResponseEntity<PrivacyChoiceResponse> chooseMember(Authentication authentication,
            @RequestBody PrivacyChoiceRequest choice) {
        Long userId = userId(authentication);
        if (userId == null) {
            throw new PrivacyException("UNAUTHORIZED", "로그인이 필요합니다.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(consents.chooseMember(userId, choice));
    }

    private void requireGuest(Authentication authentication) {
        if (userId(authentication) != null) {
            throw new PrivacyException("FORBIDDEN", "로그인 상태에서는 회원의 선택을 변경해 주세요.");
        }
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        boolean member = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"));
        if (!member) {
            throw new PrivacyException("FORBIDDEN", "이 계정으로 이용자 분석 선택을 변경할 수 없습니다.");
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0) {
                throw new NumberFormatException();
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new PrivacyException("FORBIDDEN", "이 계정의 인증 정보를 확인할 수 없습니다.");
        }
    }
}
