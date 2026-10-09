package com.toadzip.backend.user.configuration;

import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

public class SocialLoginPolicyFilter extends OncePerRequestFilter {

    private final PrivacyNoticeCatalog notices;
    private final String failureUrl;

    public SocialLoginPolicyFilter(PrivacyNoticeCatalog notices, String failureUrl) {
        this.notices = notices;
        this.failureUrl = failureUrl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/auth/oauth2/authorization/")
                && !notices.isCurrentVersion("PRIVACY_POLICY", request.getParameter("policyVersion"))) {
            response.sendRedirect(UriComponentsBuilder.fromUriString(failureUrl)
                    .queryParam("reason", "privacy-notice").build().toUriString());
            return;
        }
        chain.doFilter(request, response);
    }
}
