package com.toadzip.backend.user.service;

import org.springframework.security.core.Authentication;

public final class SocialSessions {

    private SocialSessions() {
    }

    /** 소셜 로그인 세션은 회원 번호를 이름으로 쓰고 ROLE_USER를 가진다. 비밀번호 관리자 세션은 ROLE_ADMIN만 가진다. */
    public static boolean isSocialSession(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"));
    }
}
