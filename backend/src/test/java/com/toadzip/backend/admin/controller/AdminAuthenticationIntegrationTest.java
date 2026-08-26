package com.toadzip.backend.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
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
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
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
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void CSRF_토큰_없이_로그인할_수_없다() throws Exception {
        mockMvc.perform(loginRequest("correct-password"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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
    }

    @Test
    void 잘못된_비밀번호는_관리자_계정_존재_여부를_노출하지_않는다() throws Exception {
        CsrfFixture csrfFixture = issueCsrfToken();

        mockMvc.perform(loginRequest("wrong-password")
                        .cookie(csrfFixture.cookie())
                        .header(csrfFixture.headerName(), csrfFixture.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_CREDENTIALS"));
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
        return post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginIdentifier\":\"admin\",\"password\":\"" + password + "\"}");
    }

    private record CsrfFixture(String token, String headerName, Cookie cookie) {
    }
}
