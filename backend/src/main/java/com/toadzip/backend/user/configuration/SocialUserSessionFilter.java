package com.toadzip.backend.user.configuration;

import com.toadzip.backend.admin.domain.AdminRole;
import com.toadzip.backend.global.security.SecurityErrorResponseWriter;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialSessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 소셜 로그인 세션의 요청마다 회원 행을 확인한다. 탈퇴한 회원의 세션은 끝내고,
 * 관리자 권한은 회원 행의 admin 값을 따라 이번 요청에만 붙이거나 뗀다.
 * 세션에는 ROLE_USER만 저장하므로 권한을 빼면 다음 요청부터 바로 관리자 기능이 막힌다.
 */
public class SocialUserSessionFilter extends OncePerRequestFilter {

    private static final String ADMIN_AUTHORITY = "ROLE_" + AdminRole.ADMIN.name();

    private final UserRepository users;
    private final SecurityErrorResponseWriter errors;

    public SocialUserSessionFilter(UserRepository users, SecurityErrorResponseWriter errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !SocialSessions.isSocialSession(authentication)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Boolean> admin = adminOf(authentication);
        if (admin.isEmpty()) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            response.setHeader("Cache-Control", "no-store");
            errors.write(request, response, 401, "USER_NOT_FOUND", "탈퇴한 계정입니다. 다시 로그인해 주세요.");
            return;
        }
        if (admin.get() != hasAuthority(authentication, ADMIN_AUTHORITY)) {
            useAdminAuthorityForThisRequest(authentication, admin.get());
        }
        chain.doFilter(request, response);
    }

    // 세션에 저장된 SecurityContext를 고치지 않고 이번 요청용 새 SecurityContext를 쓴다.
    private void useAdminAuthorityForThisRequest(Authentication authentication, boolean admin) {
        List<GrantedAuthority> authorities = new ArrayList<>(authentication.getAuthorities());
        authorities.removeIf(authority -> authority.getAuthority().equals(ADMIN_AUTHORITY));
        if (admin) {
            authorities.add(new SimpleGrantedAuthority(ADMIN_AUTHORITY));
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                authentication.getName(), null, authorities));
        SecurityContextHolder.setContext(context);
    }

    private Optional<Boolean> adminOf(Authentication authentication) {
        try {
            return users.findAdminById(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasAuthority(Authentication authentication, String name) {
        return authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals(name));
    }
}
