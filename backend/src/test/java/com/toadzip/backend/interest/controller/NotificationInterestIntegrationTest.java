package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationInterestIntegrationTest {

    private static final String ENDPOINT = "/api/v1/notification-subscriptions/me";
    private static final long MEMBER = 90000001L;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void member() {
        jdbc.update("INSERT INTO users (id, login_identifier, email, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                MEMBER, "privacy-notification-member", "retained@example.com");
    }

    @Test
    void 분석_거부와_무관하게_이메일없이_신청_조회_취소한다() throws Exception {
        jdbc.update("""
                INSERT INTO privacy_analytics_consents(id,user_id,decision,revision,notice_version,scope_version,
                    decided_at,created_at,updated_at)
                VALUES (?,?,'DENIED',1,'analytics-2026-10-09-v1','analytics-scope-1',now(),now(),now())
                """, UUID.randomUUID(), MEMBER);
        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request("CONFIRMED", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACTIVATED"))
                .andExpect(jsonPath("$.settingsRevision").value(1))
                .andExpect(jsonPath("$.currentTarget.active").value(true));
        mockMvc.perform(get(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(Long.toString(MEMBER)))
                .andExpect(jsonPath("$.settingsRevision").value(1))
                .andExpect(jsonPath("$.emailConfirmed").doesNotExist())
                .andExpect(jsonPath("$.targets[0].noticeVersion").value("notification-2026-10-10-v1"));
        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request("CANCELLED", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentTarget.active").value(false));
        assertEquals("retained@example.com", jdbc.queryForObject("SELECT email FROM users WHERE id = ?",
                String.class, MEMBER));
        assertEquals("DENIED", jdbc.queryForObject("SELECT decision FROM privacy_analytics_consents WHERE user_id = ?",
                String.class, MEMBER));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_email_preferences WHERE user_id = ?",
                Integer.class, MEMBER));
    }

    @Test
    void 업무요청에서_이메일과_분석식별자는_null도_거절한다() throws Exception {
        for (String field : List.of("email", "sessionId", "clientId")) {
            String body = request("CONFIRMED", 0).replace("\"eventType\"", "\"" + field + "\":null,\"eventType\"");
            mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 알림_설정은_본인_인증과_CSRF를_요구한다() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(request("CONFIRMED", 0))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(request("CONFIRMED", 0)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("CONFIRMED", 0).replace(Long.toString(MEMBER), "90000002")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTIFICATION_SETTINGS_CONFLICT"));
    }

    @Test
    void 신청은_최신_안내가_필요하며_클릭_이벤트는_업무요청이_아니다() throws Exception {
        for (String body : List.of(request("CONFIRMED", 0).replace("notification-2026-10-10-v1", "old"),
                request("CONFIRMED", 0).replace("\"noticeVersion\":\"notification-2026-10-10-v1\",", ""),
                request("CLICKED", 0))) {
            mockMvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 미동의_분석과_비회원_신청을_차단한다() throws Exception {
        for (String type : List.of("CLICKED", "EXPOSED", "DECLINED")) {
            mockMvc.perform(post("/api/v1/notification-interest-events").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(observation(type)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ANALYTICS_CONSENT_REQUIRED"));
        }
        mockMvc.perform(post("/api/v1/notification-interest-events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(observation("CONFIRMED")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/announcements/1/views").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANALYTICS_CONSENT_REQUIRED"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM privacy_notification_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM announcement_views", Integer.class));
    }

    private String observation(String type) {
        return """
                {"eventId":"%s","sessionId":"%s","eventType":"%s","source":"REGION_SEARCH",
                 "targetType":"REGION","targetId":"11"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), type);
    }

    private String request(String type, long revision) {
        return """
                {"eventId":"%s","expectedUserId":"%s","expectedSettingsRevision":%s,
                 "noticeVersion":"notification-2026-10-10-v1","eventType":"%s",
                 "source":"SETTING","targetType":"REGION","targetId":"11"}
                """.formatted(UUID.randomUUID(), MEMBER, revision, type);
    }
}
