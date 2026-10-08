package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.interest.service.NotificationRetentionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
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

    @Autowired
    private NotificationRetentionService retentionService;

    @Autowired(required = false)
    private ScheduledAnnotationBeanPostProcessor schedulingProcessor;

    @Test
    void 이메일_없는_클릭은_신청_완료와_구분된_결과를_반환한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(request(eventId, "CLICKED", "REGION_SEARCH", "REGION", "11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.targetType").value("REGION"))
                .andExpect(jsonPath("$.targetId").value("11"))
                .andExpect(jsonPath("$.outcome").value("NOT_ACTIVATED"))
                .andExpect(jsonPath("$.occurredAt").isNotEmpty());
    }

    @Test
    void 허용된_프론트엔드의_비회원_알림_조회_사전_요청을_허용한다() throws Exception {
        mockMvc.perform(options("/api/v1/notification-subscriptions/guest")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Notification-Client-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", "X-Notification-Client-Id"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void 다른_출처의_비회원_알림_조회_사전_요청을_거부한다() throws Exception {
        mockMvc.perform(options("/api/v1/notification-subscriptions/guest")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Notification-Client-Id"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 예약_작업은_알림_보관_기간_정리만_등록한다() {
        assertNotNull(schedulingProcessor);
        var scheduledMethods = schedulingProcessor.getScheduledTasks().stream()
                .map(task -> task.getTask().toString())
                .toList();

        assertEquals(List.of(NotificationRetentionService.class.getName() + ".purgeExpiredData"), scheduledMethods);
    }

    @Test
    void 보관_기간이_지난_신청과_이벤트는_자동_정리된다() {
        UUID clientId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences (client_id, email, updated_at)
                VALUES (?, 'expired@example.com', CURRENT_TIMESTAMP - INTERVAL '13 months')
                """, clientId);
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, 'REGION', '11', true, CURRENT_TIMESTAMP - INTERVAL '13 months',
                    CURRENT_TIMESTAMP - INTERVAL '1 month')
                """, clientId);
        jdbcTemplate.update("""
                INSERT INTO notification_interest_events
                    (event_id, session_id, event_type, source, target_type, target_id, created_at)
                VALUES (?, ?, 'CLICKED', 'REGION_SEARCH', 'REGION', '11', CURRENT_TIMESTAMP - INTERVAL '91 days')
                """, eventId, UUID.randomUUID());

        retentionService.purgeExpiredData();

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_email_preferences WHERE client_id = ?",
                Integer.class, clientId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_interest_events WHERE event_id = ?", Integer.class, eventId));
    }

    @Test
    void 비로그인_신청도_브라우저_식별자로_조회하고_취소한다() throws Exception {
        UUID clientId = UUID.randomUUID();
        String confirmed = request(UUID.randomUUID(), "CONFIRMED", "REGION_SEARCH", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", \"email\": \"guest@example.com\", "
                        + "\"clientId\": \"" + clientId + "\"");
        submit(confirmed);
        mockMvc.perform(get("/api/v1/notification-subscriptions/guest")
                        .header("X-Notification-Client-Id", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(true))
                .andExpect(jsonPath("$.targets[0].targetId").value("11"));

        String clicked = request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "REGION", "11680")
                .replace("\"targetId\": \"11680\"", "\"targetId\": \"11680\", "
                        + "\"clientId\": \"" + clientId + "\"");
        submit(clicked);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_subscriptions WHERE client_id = ? AND active",
                Integer.class, clientId));

        String cancelled = request(UUID.randomUUID(), "CANCELLED", "REGION_SEARCH", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", "
                        + "\"clientId\": \"" + clientId + "\"");
        submit(cancelled);
        mockMvc.perform(get("/api/v1/notification-subscriptions/guest")
                        .header("X-Notification-Client-Id", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].targetId").value("11680"));
    }

    @Test
    void 마지막_비로그인_신청_취소는_알림용_이메일도_삭제한다() throws Exception {
        UUID clientId = UUID.randomUUID();
        String confirmed = request(UUID.randomUUID(), "CONFIRMED", "REGION_SEARCH", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", \"email\": \"guest@example.com\", "
                        + "\"clientId\": \"" + clientId + "\"");
        submit(confirmed);
        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isAccepted());
        String cancelled = request(UUID.randomUUID(), "CANCELLED", "REGION_SEARCH", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", "
                        + "\"clientId\": \"" + clientId + "\"");
        submit(cancelled);

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_email_preferences WHERE client_id = ?",
                Integer.class, clientId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_cancellation_requests WHERE email = 'guest@example.com'",
                Integer.class));
        mockMvc.perform(get("/api/v1/notification-subscriptions/guest")
                        .header("X-Notification-Client-Id", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(false))
                .andExpect(jsonPath("$.targets").isEmpty());
    }

    @Test
    void 로그인_신청은_다른_요청에서도_조회되고_취소가_반영된다() throws Exception {
        long userId = 90000001L;
        jdbcTemplate.update("INSERT INTO users (id, login_identifier, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, "notification-state-test");
        String confirmed = request(UUID.randomUUID(), "CONFIRMED", "REGION_SEARCH", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", \"email\": \"member@example.com\"");
        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(userId)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmed))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notification-subscriptions/me").with(user(Long.toString(userId)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(true))
                .andExpect(jsonPath("$.targets[0].targetType").value("REGION"))
                .andExpect(jsonPath("$.targets[0].targetId").value("11"));

        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(userId)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "CLICKED", "REGION_SEARCH", "REGION", "11680")))
                .andExpect(status().isOk());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_subscriptions WHERE user_id = ? AND active", Integer.class, userId));

        mockMvc.perform(post(ENDPOINT).with(user(Long.toString(userId)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "CANCELLED", "REGION_SEARCH", "REGION", "11")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notification-subscriptions/me").with(user(Long.toString(userId)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].targetId").value("11680"));
        mockMvc.perform(get("/api/v1/notification-subscriptions/me").with(user("90000002").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmed").value(false))
                .andExpect(jsonPath("$.targets").isEmpty());
        mockMvc.perform(get("/api/v1/notification-subscriptions/me"))
                .andExpect(status().isUnauthorized());
    }

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
    void 마이그레이션은_중복_이벤트를_한번만_저장한다() throws Exception {
        String migration = new ClassPathResource("db/migration/V20260930_02__create_notification_interest_events.sql")
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
        submit(request(eventId, "CONFIRMED", "SETTING", "REGION", "11680")
                .replace("\"targetId\": \"11680\"", "\"targetId\": \"11680\", \"email\": \"guest@example.com\""));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, eventId));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notification_interest_events' AND column_name = 'email'
                """, Integer.class));

        UUID declinedId = UUID.randomUUID();
        submit(request(declinedId, "DECLINED", "SETTING", "REGION", "11680"));
        assertEquals("DECLINED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, declinedId));
    }

    @Test
    void 기존_클라이언트의_이메일_없는_확인_이벤트도_유지한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        submit(request(eventId, "CONFIRMED", "SETTING", "REGION", "11"));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, eventId));
    }

    @Test
    void 알림_취소_이벤트를_저장한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        submit(request(eventId, "CANCELLED", "REGION_SEARCH", "REGION", "11"));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_interest_events WHERE event_id = ?", String.class, eventId));
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
        reject(request(UUID.randomUUID(), "CONFIRMED", "SETTING", "REGION", "11")
                .replace("\"targetId\": \"11\"", "\"targetId\": \"11\", \"email\": \"invalid\""));
    }

    private void submit(String request) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
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
