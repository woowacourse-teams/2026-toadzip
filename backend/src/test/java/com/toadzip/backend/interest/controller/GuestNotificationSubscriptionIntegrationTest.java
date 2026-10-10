package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.privacy.service.AnalyticsConsentService;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "privacy.cookie.secure=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GuestNotificationSubscriptionIntegrationTest {

    private static final String ENDPOINT = "/api/v1/notification-subscriptions/guest";
    private static final String CLIENT_HEADER = "X-Notification-Client-Id";
    private static final List<String> TABLES = List.of("notification_guest_email_preferences",
            "notification_guest_subscriptions", "notification_interest_events", "privacy_analytics_consents",
            "privacy_analytics_consent_events", "privacy_notification_events", "privacy_registration_notices",
            "privacy_notification_states", "privacy_notification_notices", "privacy_notification_receipts");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AnalyticsConsentService consents;

    @Test
    void 로그인과_분석동의_없이_기존_헤더와_응답으로_활성_구독만_조회한다() throws Exception {
        UUID clientId = seed();
        Map<String, List<String>> before = snapshot();

        mvc.perform(get(ENDPOINT).header(CLIENT_HEADER, clientId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.emailConfirmed").value(true))
                .andExpect(jsonPath("$.targets.length()").value(1))
                .andExpect(jsonPath("$.targets[0].targetType").value("REGION"))
                .andExpect(jsonPath("$.targets[0].targetId").value("11"))
                .andExpect(jsonPath("$.targets[0].targetName").isEmpty())
                .andExpect(jsonPath("$.email").doesNotExist());

        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "WITHDRAW"})
    void 거부하거나_철회한_브라우저도_기존_구독을_조회하고_자료를_변경하지_않는다(String action) throws Exception {
        UUID clientId = seed();
        var guest = consents.prepareGuest(null);
        long revision = 0;
        if (action.equals("WITHDRAW")) {
            consents.chooseGuest(guest.token(), choice(guest.context().subject().contextId(), revision, "GRANT"));
            revision++;
        }
        consents.chooseGuest(guest.token(), choice(guest.context().subject().contextId(), revision, action));
        String decision = "DENIED";
        if (action.equals("WITHDRAW")) {
            decision = "WITHDRAWN";
        }
        mvc.perform(get("/api/v1/privacy/analytics-context")
                        .cookie(new Cookie("__Host-toadzip-privacy", guest.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consent.effectiveStatus").value(decision))
                .andExpect(jsonPath("$.collectionAllowed").value(false));
        Map<String, List<String>> before = snapshot();

        mvc.perform(get(ENDPOINT).header(CLIENT_HEADER, clientId)
                        .cookie(new Cookie("__Host-toadzip-privacy", guest.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(true))
                .andExpect(jsonPath("$.targets[0].targetId").value("11"));

        assertEquals(before, snapshot());
    }

    @Test
    void 모르는_브라우저에는_빈_목록을_반환한다() throws Exception {
        seed();
        mvc.perform(get(ENDPOINT).header(CLIENT_HEADER, UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(false))
                .andExpect(jsonPath("$.targets").isEmpty());
    }

    @Test
    void 이메일만_남고_유효한_구독이_없으면_확인여부와_목록은_비어있다() throws Exception {
        UUID clientId = seed();
        jdbc.update("UPDATE notification_guest_subscriptions SET active = false WHERE client_id = ?", clientId);
        Map<String, List<String>> before = snapshot();

        mvc.perform(get(ENDPOINT).header(CLIENT_HEADER, clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(false))
                .andExpect(jsonPath("$.targets").isEmpty());

        assertEquals(before, snapshot());
    }

    @Test
    void 필수_헤더_누락과_UUID_오류는_400이다() throws Exception {
        mvc.perform(get(ENDPOINT)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(ENDPOINT).header(CLIENT_HEADER, "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 기존_프론트_출처와_조회_헤더의_CORS를_유지한다() throws Exception {
        mvc.perform(options(ENDPOINT).header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", CLIENT_HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", CLIENT_HEADER))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options(ENDPOINT).header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", CLIENT_HEADER))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private UUID seed() {
        UUID clientId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_guest_email_preferences(client_id,email,updated_at)
                VALUES (?, 'guest@example.invalid', CURRENT_TIMESTAMP)
                """, clientId);
        jdbc.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (?, 'REGION', '11', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day'),
                    (?, 'REGION', '26', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day'),
                    (?, 'REGION', '27', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '1 day')
                """, clientId, clientId, clientId);
        UUID other = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_guest_email_preferences(client_id,email,updated_at)
                VALUES (?, 'other@example.invalid', CURRENT_TIMESTAMP)
                """, other);
        jdbc.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (?, 'REGION', '28', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, other);
        return clientId;
    }

    private PrivacyChoiceRequest choice(String contextId, long revision, String action) {
        return new PrivacyChoiceRequest(UUID.randomUUID().toString(), null, contextId, revision, action,
                "analytics-2026-10-09-v2", "analytics-scope-2", "SETTINGS");
    }

    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForList(
                    "SELECT to_jsonb(records)::text FROM " + table + " records ORDER BY to_jsonb(records)::text",
                    String.class));
        }
        return snapshot;
    }
}
