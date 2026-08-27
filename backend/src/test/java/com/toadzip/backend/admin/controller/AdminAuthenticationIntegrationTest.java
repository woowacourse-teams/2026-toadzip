package com.toadzip.backend.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditAction;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditLog;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditResult;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import com.toadzip.backend.admin.repository.AdminAuthenticationAuditLogRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminAccountRepository adminAccountRepository;

    @Autowired
    private AdminAuthenticationAuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        adminAccountRepository.deleteAll();
        adminAccountRepository.save(
                AdminAccount.create(
                        "admin",
                        passwordEncoder.encode("correct-password"),
                        LocalDateTime.of(2026, 8, 26, 10, 0)
                )
        );
    }

    @Test
    void 비로그인_관리자_조회는_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("관리자 로그인이 필요합니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void CSRF_토큰_없이_로그인할_수_없다() throws Exception {
        mockMvc.perform(loginRequest("correct-password"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 관리자_권한이_아닌_인증_주체는_403_오류_계약을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").with(user("member").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 관리자는_로그인한_세션으로_현재_정보를_조회하고_로그아웃할_수_있다() throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();
        MvcResult loginResult = mockMvc.perform(loginRequest("correct-password")
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value("admin"));

        mockMvc.perform(post("/api/admin/auth/logout")
                        .session(session)
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        assertAuditLog(AdminAuthenticationAuditAction.LOGIN, AdminAuthenticationAuditResult.SUCCESS);
        assertAuditLog(AdminAuthenticationAuditAction.LOGOUT, AdminAuthenticationAuditResult.SUCCESS);
    }

    @Test
    void 잘못된_비밀번호는_관리자_계정_존재_여부를_노출하지_않는다() throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();

        mockMvc.perform(loginRequest("wrong-password")
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("관리자 로그인 정보가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertAuditLog(AdminAuthenticationAuditAction.LOGIN, AdminAuthenticationAuditResult.FAILURE);
    }

    @Test
    void 같은_IP와_식별자의_여섯_번째_로그인_시도는_429를_반환한다() throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(loginRequest("rate-limited-admin", "wrong-password")
                            .cookie(csrfFixture.cookie())
                            .header(csrfFixture.headerName(), csrfFixture.token()))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest("rate-limited-admin", "wrong-password")
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_ATTEMPTS_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 로그인_요청값_검증_실패는_공통_오류_계약을_반환한다() throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginIdentifier\":\"\",\"password\":\"\"}")
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    private void assertAuditLog(
            AdminAuthenticationAuditAction action,
            AdminAuthenticationAuditResult result
    ) {
        AdminAuthenticationAuditLog auditLog = auditLogRepository.findAll()
                .stream()
                .filter(log -> log.getAction() == action && log.getResult() == result)
                .findFirst()
                .orElseThrow();
        assertEquals("admin", auditLog.getLoginIdentifier());
        org.assertj.core.api.Assertions.assertThat(auditLog.getRequestTraceId()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(auditLog.getOccurredAt()).isNotNull();
    }

    private CsrfFixture issueCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        return new CsrfFixture(response.get("token").asText(), response.get("headerName").asText(), cookie);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String password) {
        return loginRequest("admin", password);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            String loginIdentifier,
            String password
    ) {
        return post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginIdentifier\":\"" + loginIdentifier + "\",\"password\":\"" + password + "\"}");
    }

    private record CsrfFixture(String token, String headerName, Cookie cookie) {
    }
}
