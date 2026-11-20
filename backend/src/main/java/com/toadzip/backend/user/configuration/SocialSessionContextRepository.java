package com.toadzip.backend.user.configuration;

import com.toadzip.backend.user.service.SocialSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/** 소셜 로그인 성공 핸들러가 발급한 회원 인증만 HTTP 세션에서 복원한다. */
public final class SocialSessionContextRepository implements SecurityContextRepository {

    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    // SecurityContextRepository의 기본 지연 로더도 이 메서드로 복원한다.
    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        SecurityContext stored = sessions.loadDeferredContext(holder.getRequest()).get();
        if (isSocial(stored)) {
            return stored;
        }
        return SecurityContextHolder.createEmptyContext();
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return isSocial(sessions.loadDeferredContext(request).get());
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // OAuth 공급자 인증은 저장하지 않는다. 앱에서 세션을 종료할 때는 빈 컨텍스트를 반영한다.
        if (context.getAuthentication() == null || isSocial(context)) {
            sessions.saveContext(context, request, response);
        }
    }

    private boolean isSocial(SecurityContext context) {
        return context.getAuthentication() instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof String
                && SocialSessions.isSocialSession(authentication);
    }
}
