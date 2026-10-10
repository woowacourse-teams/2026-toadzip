package com.toadzip.backend.user.configuration;

import com.toadzip.backend.global.security.SecurityErrorResponseWriter;
import com.toadzip.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public class DeletedUserSessionFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final SecurityErrorResponseWriter errors;

    public DeletedUserSessionFilter(UserRepository users, SecurityErrorResponseWriter errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER")) && !exists(authentication)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            response.setHeader("Cache-Control", "no-store");
            errors.write(request, response, 401, "USER_NOT_FOUND", "탈퇴한 계정입니다. 다시 로그인해 주세요.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean exists(Authentication authentication) {
        try {
            return users.existsById(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
