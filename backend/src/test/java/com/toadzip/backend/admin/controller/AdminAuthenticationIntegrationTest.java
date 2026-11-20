package com.toadzip.backend.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
// csrf() in other cached MockMvc contexts replaces the filter's real cookie repository.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AdminAuthenticationIntegrationTest {

    @Autowired
    private UserRepository memberRepository;

    private String memberId() {
        var member = User.create(
                "google:permission-" + UUID.randomUUID(), LocalDateTime.now());
        return memberRepository.saveAndFlush(member).getId().toString();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 기존_비밀번호_세션으로_관리_화면과_관리_API에_접근할_수_없다() throws Exception {
        var legacyAdmin = org.springframework.security.core.userdetails.User.withUsername("admin")
                .password("unused").roles("ADMIN").build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                legacyAdmin, null, legacyAdmin.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        for (String path : List.of("/api/admin/auth/me", "/api/admin/operations/complexes")) {
            mockMvc.perform(get(path).session(session)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 공식_일정과_정정_등록은_관리자_권한과_CSRF를_요구한다() throws Exception {
        for (String operation : List.of("application-schedules", "revision")) {
            String path = "/api/admin/announcements/42/" + operation;
            CsrfFixture csrfFixture = issueCsrfToken();
            mockMvc.perform(put(path).cookie(csrfFixture.cookie())
                            .header(csrfFixture.headerName(), csrfFixture.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(put(path).cookie(csrfFixture.cookie())
                            .header(csrfFixture.headerName(), csrfFixture.token()).with(user(memberId()).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(path).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(path).cookie(csrfFixture.cookie())
                            .header(csrfFixture.headerName(), csrfFixture.token()).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
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
                .andExpect(jsonPath("$.message").value("요청 보안 토큰이 유효하지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 관리자_권한이_아닌_인증_주체는_403_오류_계약을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").with(user(memberId()).roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 유효한_CSRF가_있어도_비밀번호로_세션을_만들_수_없다() throws Exception {
        CsrfFixture csrf = issueCsrfToken();
        mockMvc.perform(loginRequest("correct-password")
                        .cookie(csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private CsrfFixture issueCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        return new CsrfFixture(response.get("token").asText(), response.get("headerName").asText(), cookie);
    }

    private MockHttpServletRequestBuilder loginRequest(String password) {
        return post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginIdentifier\":\"admin\",\"password\":\"" + password + "\"}");
    }

    private record CsrfFixture(String token, String headerName, Cookie cookie) {
    }
}
