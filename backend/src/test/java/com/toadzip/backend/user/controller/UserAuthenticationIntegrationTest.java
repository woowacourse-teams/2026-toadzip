package com.toadzip.backend.user.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestResolver;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import com.toadzip.backend.user.service.SocialUserService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.toadzip.backend.privacy.service.AnalyticsConsentService;
import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "app.user.oauth.enabled=true",
        "app.user.oauth.google.client-id=test-google-client",
        "app.user.oauth.google.client-secret=test-google-secret",
        "app.user.oauth.kakao.client-id=test-kakao-client",
        "app.user.oauth.kakao.client-secret=test-kakao-secret",
        "app.user.oauth.redirect-base-url=http://localhost:8080"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SocialLoginSuccessHandler successHandler;

    @Autowired
    private SocialUserService socialUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PrivacyNoticeCatalog notices;

    @Autowired
    private Clock clock;

    @Autowired
    private AnalyticsConsentService consents;

    @Test
    void 각_공급자_로그인은_고정된_콜백과_state로_시작한다() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/authorization/google")
                        .param("policyVersion", notices.currentVersion("PRIVACY_POLICY")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("accounts.google.com"),
                        org.hamcrest.Matchers.containsString("state="),
                        org.hamcrest.Matchers.containsString("callback/google")
                )));

        mockMvc.perform(get("/api/auth/oauth2/authorization/kakao")
                        .param("policyVersion", notices.currentVersion("PRIVACY_POLICY")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("kauth.kakao.com"),
                        org.hamcrest.Matchers.containsString("state="),
                        org.hamcrest.Matchers.containsString("callback/kakao")
                )));
    }

    @Test
    void 유효하지_않은_콜백은_로그인에_실패한다() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/callback/kakao")
                        .param("code", "invalid-code")
                        .param("state", "unknown-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/?login=failed"));
    }

    @Test
    void 유효하지_않은_콜백은_기존_관리자_로그인을_종료하지_않는다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(get("/api/auth/oauth2/callback/kakao")
                        .session(session)
                        .param("code", "invalid-code")
                        .param("state", "unknown-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/?login=failed"));
        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인_사용자는_현재_세션을_조회하고_로그아웃한다() throws Exception {
        MockHttpServletRequest request = loginRequest("kakao");
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(
                request, response, authentication("kakao", Map.of("id", 1890123L,
                        "kakao_account", Map.of("email", "kakao@example.com")), "id"));
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        Long id = userRepository.findByLoginIdentifier("kakao:1890123").orElseThrow().getId();

        assertEquals("http://localhost:5173/", response.getRedirectedUrl());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("kakao@example.com"));
        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_공급자_계정은_기존_유저를_사용하고_공급자가_다르면_분리한다() {
        Long first = socialUserService.findOrCreate("google", "google-sub-189", null, authorization());
        Long repeated = socialUserService.findOrCreate(
                "google", "google-sub-189", "member@example.com", authorization());
        Long kakao = socialUserService.findOrCreate("kakao", "google-sub-189", null, authorization());

        assertEquals(first, repeated);
        assertEquals("member@example.com", userRepository.findById(first).orElseThrow().getEmail());
        assertNotEquals(first, kakao);
    }

    @Test
    void DB가_중복_로그인_식별정보를_거부한다() {
        userRepository.saveAndFlush(User.create("kakao:unique-189", LocalDateTime.of(2026, 9, 22, 10, 0)));

        assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(
                User.create("kakao:unique-189", LocalDateTime.of(2026, 9, 22, 10, 1))));
    }

    @Test
    void 구글_로그인은_검증된_sub를_사용한다() throws Exception {
        MockHttpServletRequest request = loginRequest("google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request, response, authentication("google", Map.of("sub", "google-sub-190",
                        "email", "google@example.com"), "sub"));

        assertEquals("http://localhost:5173/", response.getRedirectedUrl());
        Long id = userRepository.findByLoginIdentifier("google:google-sub-190").orElseThrow().getId();
        mockMvc.perform(get("/api/auth/me").session((MockHttpSession) request.getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("google@example.com"));
    }

    @Test
    void 로그아웃에는_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(user("189").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 식별정보가_없는_응답은_사용자_계정을_만들지_않는다() throws Exception {
        MockHttpServletRequest request = loginRequest("kakao");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request, response, authentication("kakao", Map.of("sub", "not-an-id"), "sub"));

        assertEquals("http://localhost:5173/?login=failed", response.getRedirectedUrl());
        assertNull(request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void 인증이_없거나_관리자만_인증한_경우_사용자_조회는_거부된다() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequest loginRequest(String provider) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/oauth2/callback/" + provider);
        request.setSession(new MockHttpSession());
        request.setAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE, authorization());
        return request;
    }

    private SocialAuthorizationContext authorization() {
        return new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"), clock.instant());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"privacy-2026-10-09-v1", "unknown"})
    void 정책_버전_누락과_구버전도_기존_로그인_URL로_인가를_시작한다(String version) throws Exception {
        for (String provider : java.util.List.of("google", "kakao")) {
            var request = get("/api/auth/oauth2/authorization/" + provider);
            if (version != null) {
                request.param("policyVersion", version);
            }
            mockMvc.perform(request)
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.containsString("state="),
                            org.hamcrest.Matchers.containsString("callback/" + provider))));
        }
    }

    @Test
    void 분석을_거부한_기존_회원도_정책없이_로그인하고_세션을_회전한다() throws Exception {
        String subject = UUID.randomUUID().toString();
        Long id = socialUserService.findOrCreate("google", subject, "first@example.com", authorization());
        consents.chooseMember(id, new PrivacyChoiceRequest(UUID.randomUUID().toString(), id.toString(), null,
                0L, "DENY", null, null, "SETTINGS"));
        var before = consents.context(id, null).consent();
        var request = loginRequest("google");
        String previousSessionId = request.getSession().getId();
        request.setAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE,
                new SocialAuthorizationContext(null, clock.instant()));
        var response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response,
                authentication("google", Map.of("sub", subject, "email", "updated@example.com"), "sub"));

        assertEquals("http://localhost:5173/", response.getRedirectedUrl());
        assertNotEquals(previousSessionId, request.getSession().getId());
        var session = (MockHttpSession) request.getSession();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("updated@example.com"));
        mockMvc.perform(get("/api/v1/privacy/analytics-context").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consent.decision").value("DENIED"))
                .andExpect(jsonPath("$.collectionAllowed").value(false));
        assertEquals(before, consents.context(id, null).consent());
    }

    @Test
    void 만료된_OAuth_state는_정책_누락과_무관하게_콜백에서_거부된다() throws Exception {
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var authorization = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.example/authorize").clientId("test-client")
                .redirectUri("http://localhost/api/auth/oauth2/callback/google").state("expired-state")
                .attributes(attributes -> attributes.put(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE,
                        new SocialAuthorizationContext(null, clock.instant().minusSeconds(600)))).build();
        new SocialAuthorizationRequestRepository(clock).saveAuthorizationRequest(
                authorization, request, new MockHttpServletResponse());

        mockMvc.perform(get("/api/auth/oauth2/callback/google").session(session)
                        .param("code", "unused-code").param("state", "expired-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/?login=failed"));
        assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void 없는_회원의_세션은_여전히_무효화된다() throws Exception {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "9223372036854775807", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertTrue(session.isInvalid());
    }

    @Test
    void 검증된_인가_요청이_없는_콜백은_계정을_만들지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(request, response,
                authentication("google", Map.of("sub", "missing-policy-context"), "sub"));
        assertEquals("http://localhost:5173/?login=failed", response.getRedirectedUrl());
        assertEquals(false, userRepository.findByLoginIdentifier("google:missing-policy-context").isPresent());
    }

    private OAuth2AuthenticationToken authentication(
            String provider, Map<String, Object> attributes, String nameKey
    ) {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, nameKey);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), provider);
    }
}
