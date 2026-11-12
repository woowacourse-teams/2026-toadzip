package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.domain.AdminAuthenticationAuditAction;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditLog;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditResult;
import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminAuthenticationAuditLogRepository;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.admin.service.AdminAuthenticationAuditService;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestResolver;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
// csrf() in other cached MockMvc contexts replaces the filter's real cookie repository.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AdminSocialLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminAuthenticationAuditLogRepository auditLogRepository;

    @MockitoSpyBean
    private AdminAuthenticationAuditService auditService;

    @Autowired
    private SocialLoginSuccessHandler successHandler;

    @Autowired
    private PrivacyNoticeCatalog notices;

    @Autowired
    private Clock clock;

    @Autowired
    private AdminDataChangeRepository dataChanges;

    @Autowired
    private JdbcTemplate jdbc;

    private final java.util.List<Long> createdUsers = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(auditService);
        auditLogRepository.deleteAll();
    }

    // 다른 테스트 클래스가 같은 DB에서 회원 수를 세므로 만든 회원을 지운다.
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (Long id : createdUsers) {
            jdbc.update("DELETE FROM admin_data_changes WHERE resource_type = 'USER' AND resource_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void 관리자가_권한을_주면_다시_로그인하지_않아도_바로_관리자가_되고_기록이_남는다() throws Exception {
        String subject = newSubject();
        MockHttpSession session = socialLogin("kakao", Map.of("id", subject), "id");
        long userId = userIdOf(session);
        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        mockMvc.perform(grant(userId).with(user("root").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.admin").value(true));

        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value("kakao:" + subject))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        // 관리자도 같은 계정의 사용자 기능을 그대로 쓴다.
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        assertThat(changesOf(userId)).extracting(AdminDataChange::getAction, AdminDataChange::getActor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GRANT_ADMIN", "root"));

        // 이미 관리자면 기록을 더 남기지 않는다.
        mockMvc.perform(grant(userId).with(user("root").roles("ADMIN"))).andExpect(status().isOk());
        assertThat(changesOf(userId)).hasSize(1);
    }

    @Test
    void 권한을_빼면_로그인해_있던_세션도_다음_요청부터_관리자_기능이_막힌다() throws Exception {
        MockHttpSession actor = adminSocialLogin();
        MockHttpSession target = adminSocialLogin();
        long targetId = userIdOf(target);
        mockMvc.perform(get("/api/admin/users/" + targetId).session(target)).andExpect(status().isOk());
        // 관리자 요청을 거친 뒤에도 세션에는 관리자 권한이 저장되지 않는다.
        assertThat(authoritiesOf(target)).containsExactly("ROLE_USER");

        mockMvc.perform(revoke(targetId).session(actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admin").value(false));

        mockMvc.perform(get("/api/admin/users/" + targetId).session(target)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/me").session(target)).andExpect(status().isOk());
        assertThat(changesOf(targetId)).extracting(AdminDataChange::getAction, AdminDataChange::getActor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GRANT_ADMIN", "root"),
                        org.assertj.core.groups.Tuple.tuple("REVOKE_ADMIN", Long.toString(userIdOf(actor))));
    }

    @Test
    void 자기_자신의_관리자_권한은_뺄_수_없다() throws Exception {
        MockHttpSession session = adminSocialLogin();
        long userId = userIdOf(session);

        mockMvc.perform(revoke(userId).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_ADMIN_REVOKE"))
                .andExpect(jsonPath("$.message").value("자기 자신의 관리자 권한은 뺄 수 없습니다."));

        mockMvc.perform(get("/api/admin/auth/me").session(session)).andExpect(status().isOk());
        assertThat(changesOf(userId)).hasSize(1);
    }

    @Test
    void 관리자가_아닌_회원은_권한을_줄_수_없고_없는_회원은_404다() throws Exception {
        MockHttpSession member = socialLogin("google", Map.of("sub", newSubject()), "sub");
        long memberId = userIdOf(member);

        mockMvc.perform(grant(memberId).session(member)).andExpect(status().isForbidden());
        mockMvc.perform(grant(Long.MAX_VALUE).with(user("root").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(changesOf(memberId)).isEmpty();
    }

    @Test
    void 관리자_권한을_받은_소셜_계정의_로그인과_사이트_로그아웃은_감사_기록에_남는다() throws Exception {
        String subject = newSubject();
        MockHttpSession first = socialLogin("kakao", Map.of("id", subject), "id");
        mockMvc.perform(grant(userIdOf(first)).with(user("root").roles("ADMIN"))).andExpect(status().isOk());
        auditLogRepository.deleteAll();

        MockHttpSession session = socialLogin("kakao", Map.of("id", subject), "id");
        mockMvc.perform(logout(session)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/auth/me").session(session)).andExpect(status().isUnauthorized());

        assertAuditLog(AdminAuthenticationAuditAction.LOGIN, "kakao:" + subject);
        assertAuditLog(AdminAuthenticationAuditAction.LOGOUT, "kakao:" + subject);
    }

    @Test
    void 로그아웃_감사_기록이_실패해도_관리자_세션은_끝난다() throws Exception {
        MockHttpSession session = adminSocialLogin();
        doThrow(new IllegalStateException("audit down")).when(auditService).recordLogout(any(), any());

        mockMvc.perform(logout(session))
                .andExpect(status().is5xxServerError());
        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 소셜_관리자가_관리자_화면에서_로그아웃해도_사이트_세션이_끝나고_감사_기록이_남는다() throws Exception {
        MockHttpSession session = adminSocialLogin();
        String loginIdentifier = jdbc.queryForObject("SELECT login_identifier FROM users WHERE id = ?",
                String.class, userIdOf(session));
        CsrfFixture csrfFixture = issueCsrfToken();

        mockMvc.perform(post("/api/admin/auth/logout").session(session)
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());

        assertAuditLog(AdminAuthenticationAuditAction.LOGOUT, loginIdentifier);
    }

    @Test
    void 관리자가_아닌_소셜_계정의_로그인과_로그아웃은_감사_기록에_남지_않는다() throws Exception {
        MockHttpSession session = socialLogin("google", Map.of("sub", newSubject()), "sub");

        mockMvc.perform(logout(session)).andExpect(status().isNoContent());

        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    @Test
    void 권한을_뺏긴_관리자는_다른_관리자의_권한을_뺄_수_없다() throws Exception {
        MockHttpSession revoked = adminSocialLogin();
        MockHttpSession other = adminSocialLogin();
        long otherId = userIdOf(other);
        jdbc.update("UPDATE users SET admin = false WHERE id = ?", userIdOf(revoked));

        mockMvc.perform(revoke(otherId).session(revoked)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/auth/me").session(other)).andExpect(status().isOk());
        assertThat(changesOf(otherId)).hasSize(1);
    }

    @Test
    void 회원_행이_관리자가_아니면_세션에_관리자_권한이_있어도_관리자_기능이_막힌다() throws Exception {
        MockHttpSession session = socialLogin("kakao", Map.of("id", newSubject()), "id");

        mockMvc.perform(get("/api/admin/auth/me").with(user(Long.toString(userIdOf(session))).roles("USER", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    /** 비밀번호 관리자가 권한을 준 뒤 새로 로그인한 소셜 관리자 세션. */
    private MockHttpSession adminSocialLogin() throws Exception {
        MockHttpSession session = socialLogin("kakao", Map.of("id", newSubject()), "id");
        mockMvc.perform(grant(userIdOf(session)).with(user("root").roles("ADMIN"))).andExpect(status().isOk());
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder grant(long userId)
            throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();
        return put("/api/admin/users/" + userId + "/admin")
                .cookie(csrfFixture.cookie())
                .header(csrfFixture.headerName(), csrfFixture.token());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder revoke(long userId)
            throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();
        return delete("/api/admin/users/" + userId + "/admin")
                .cookie(csrfFixture.cookie())
                .header(csrfFixture.headerName(), csrfFixture.token());
    }

    private java.util.List<AdminDataChange> changesOf(long userId) {
        return dataChanges.findAll().stream()
                .filter(change -> change.getResourceType().equals("USER") && change.getResourceId() == userId)
                .sorted(java.util.Comparator.comparing(AdminDataChange::getId))
                .toList();
    }

    private static String newSubject() {
        return UUID.randomUUID().toString();
    }

    private static SecurityContext contextOf(MockHttpSession session) {
        return (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }

    private static long userIdOf(MockHttpSession session) {
        return Long.parseLong(contextOf(session).getAuthentication().getName());
    }

    private static java.util.List<String> authoritiesOf(MockHttpSession session) {
        return contextOf(session).getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    private MockHttpSession socialLogin(String provider, Map<String, Object> attributes, String nameKey)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/oauth2/callback/" + provider);
        request.setSession(new MockHttpSession());
        request.setAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE,
                new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"), clock.instant()));
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, nameKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), provider));
        // 처리기가 테스트 스레드에 남긴 로그인 정보가 다음 MockMvc 요청의 인증으로 쓰이지 않게 지운다.
        SecurityContextHolder.clearContext();
        assertThat(response.getRedirectedUrl()).doesNotContain("login=failed");
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        long userId = userIdOf(session);
        if (!createdUsers.contains(userId)) {
            createdUsers.add(userId);
        }
        return session;
    }

    private void assertAuditLog(AdminAuthenticationAuditAction action, String loginIdentifier) {
        AdminAuthenticationAuditLog auditLog = auditLogRepository.findAll()
                .stream()
                .filter(log -> log.getAction() == action)
                .findFirst()
                .orElseThrow();
        assertThat(auditLog.getResult()).isEqualTo(AdminAuthenticationAuditResult.SUCCESS);
        assertThat(auditLog.getLoginIdentifier()).isEqualTo(loginIdentifier);
        assertThat(auditLog.getRequestTraceId()).isNotBlank();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logout(MockHttpSession session)
            throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();
        return post("/api/auth/logout").session(session)
                .cookie(csrfFixture.cookie())
                .header(csrfFixture.headerName(), csrfFixture.token());
    }

    private CsrfFixture issueCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new CsrfFixture(response.get("token").asText(), response.get("headerName").asText(), cookie);
    }

    private record CsrfFixture(String token, String headerName, Cookie cookie) {
    }
}
