package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationInterestIntegrationTest {

    private static final String ENDPOINT = "/api/v1/notification-interest-events";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationInterestRepository repository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 병렬_재전송도_하나의_이벤트만_저장한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        NotificationInterestEvent event = NotificationInterestEvent.create(eventId, UUID.randomUUID(),
                NotificationEventType.CLICKED, NotificationEventSource.REGION_SEARCH,
                NotificationTargetType.REGION, "11", Instant.now());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> repository.record(event));
            var second = executor.submit(() -> repository.record(event));
            first.get();
            second.get();
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_interest_events WHERE event_id = ?", Integer.class, eventId));
        } finally {
            jdbcTemplate.update("DELETE FROM notification_interest_events WHERE event_id = ?", eventId);
        }
    }

    @Test
    void 마이그레이션은_이벤트_중복과_미지원_유형을_거절한다() throws Exception {
        String migration = new ClassPathResource("db/migration/V20260927_01__create_notification_interest_events.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(migration.replace("notification_interest_events", "notification_interest_migration_test"));
        UUID eventId = UUID.randomUUID();
        String insert = """
                INSERT INTO notification_interest_migration_test
                (event_id, session_id, event_type, source, target_type, target_id, created_at)
                VALUES (?, ?, 'CLICKED', 'REGION_SEARCH', 'REGION', '11', CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """;
        jdbcTemplate.update(insert, eventId, UUID.randomUUID());
        jdbcTemplate.update(insert, eventId, UUID.randomUUID());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_interest_migration_test",
                Integer.class));
    }

    @Test
    void 익명_클릭을_저장하고_같은_이벤트_재전송은_중복_저장하지_않는다() throws Exception {
        UUID eventId = UUID.randomUUID();
        String request = request(eventId, "CLICKED", "REGION_SEARCH", "REGION", "11");

        submit(request);
        submit(request);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_interest_events WHERE event_id = ?", Integer.class, eventId));
        assertEquals("CLICKED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, eventId));
    }

    @Test
    void 최초_확인과_거절을_클릭과_구분하여_저장한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        submit(request(eventId, "CONFIRMED", "SETTING", "REGION", "11680"));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, eventId));

        UUID declinedId = UUID.randomUUID();
        submit(request(declinedId, "DECLINED", "SETTING", "REGION", "11680"));
        assertEquals("DECLINED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, declinedId));
    }

    @Test
    void 익명_요청도_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "REGION", "11")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").isNotEmpty());
    }

    @Test
    void 필수값과_대상_형식과_존재여부를_검증한다() throws Exception {
        reject(request(UUID.randomUUID(), "UNKNOWN", "REGION_SEARCH", "REGION", "11"));
        reject(request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "REGION", "99999"));
        reject(request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "COMPLEX", "1"));
        reject(request(UUID.randomUUID(), "CLICKED", "SETTING", "COMPLEX", "0"));
        reject(request(UUID.randomUUID(), "CLICKED", "SETTING", "ANNOUNCEMENT", "1"));
        reject(request(UUID.randomUUID(), "CLICKED", "COMPLEX_DETAIL", "COMPLEX", "9223372036854775807"));
        reject(request(UUID.randomUUID(), "CLICKED", "ANNOUNCEMENT_DETAIL", "ANNOUNCEMENT", "-1"));
        reject(request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "REGION", "11")
                .replace("\"sessionId\": \"00000000-0000-4000-8000-000000000001\"", "\"sessionId\": null"));
    }

    private void submit(String request) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
    }

    private void reject(String request) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
    }

    private String request(UUID eventId, String type, String source, String targetType, String targetId) {
        return """
                {
                  "eventId": "%s",
                  "sessionId": "00000000-0000-4000-8000-000000000001",
                  "eventType": "%s",
                  "source": "%s",
                  "targetType": "%s",
                  "targetId": "%s"
                }
                """.formatted(eventId, type, source, targetType, targetId);
    }
}
