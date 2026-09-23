package com.toadzip.backend.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.global.security.SecurityConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

class SocialLoginFailureHandlerTest {

    @Test
    void 공급자_인증_실패는_기존_세션을_유지한다() throws Exception {
        SocialLoginFailureHandler handler = new SocialLoginFailureHandler();
        ReflectionTestUtils.setField(handler, "failureUrl", "/login?login=failed");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, existingContext);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_state")));

        assertSame(session, request.getSession(false));
        assertSame(existingContext,
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertEquals("/login?login=failed", response.getRedirectedUrl());
    }

    @Test
    void 계정_처리_실패도_기존_세션을_유지한다() throws Exception {
        SecurityContextRepository repository = new SecurityConfiguration().securityContextRepository();
        SocialLoginFailureHandler failureHandler = new SocialLoginFailureHandler();
        ReflectionTestUtils.setField(failureHandler, "failureUrl", "/login?login=failed");
        SocialUserService userService = mock(SocialUserService.class);
        when(userService.findOrCreate("kakao", "123")).thenThrow(new IllegalStateException("database unavailable"));
        SocialLoginSuccessHandler successHandler = new SocialLoginSuccessHandler(
                userService, repository, failureHandler);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, existingContext);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("id", 123), "id");
        OAuth2AuthenticationToken oauth = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "kakao");
        SecurityContext oauthContext = SecurityContextHolder.createEmptyContext();
        oauthContext.setAuthentication(oauth);

        repository.saveContext(oauthContext, request, response);
        try {
            successHandler.onAuthenticationSuccess(request, response, oauth);

            assertSame(session, request.getSession(false));
            assertSame(existingContext,
                    session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
            assertEquals("/login?login=failed", response.getRedirectedUrl());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
