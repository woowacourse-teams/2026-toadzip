package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import com.toadzip.backend.interest.repository.NotificationGuestSubscriptionRepository;
import com.toadzip.backend.interest.repository.NotificationSubscriptionRepository;
import com.toadzip.backend.interest.service.NotificationInterestService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationInterestOutcomeIntegrationTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Autowired
    private NotificationInterestService service;

    @Autowired
    private NotificationSubscriptionRepository subscriptions;

    @Autowired
    private NotificationGuestSubscriptionRepository guestSubscriptions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 단지_신청_결과에_식별자와_시각만_담고_개인정보를_제외한다() throws Exception {
        long complexId = createComplex();
        NotificationInterestRequest request = new NotificationInterestRequest(UUID.randomUUID(), SESSION_ID,
                NotificationEventType.CONFIRMED, NotificationEventSource.COMPLEX_DETAIL,
                NotificationTargetType.COMPLEX, Long.toString(complexId), "guest@example.com", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/notification-interest-events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(request.eventId().toString()))
                .andExpect(jsonPath("$.targetType").value("COMPLEX"))
                .andExpect(jsonPath("$.targetId").value(Long.toString(complexId)))
                .andExpect(jsonPath("$.outcome").value("ACTIVATED"))
                .andExpect(jsonPath("$.occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.clientId").doesNotExist())
                .andExpect(jsonPath("$.requestFingerprint").doesNotExist());
    }

    @Test
    void 회원의_활성_신청은_완료를_반복하지_않고_보관기간만_갱신한다() {
        long userId = createUser();
        NotificationInterestRequest request = request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "member@example.com", null);
        assertEquals(NotificationInterestOutcome.ACTIVATED, service.record(request, userId).outcome());
        jdbcTemplate.update("""
                UPDATE notification_subscriptions SET expires_at = CURRENT_TIMESTAMP + INTERVAL '1 month'
                WHERE user_id = ?
                """, userId);

        NotificationInterestResponse renewed = service.record(
                request(UUID.randomUUID(), NotificationEventType.CLICKED, "11", null, null), userId);

        assertEquals(NotificationInterestOutcome.ALREADY_ACTIVE, renewed.outcome());
        Instant expiry = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM notification_subscriptions WHERE user_id = ?", Timestamp.class, userId)
                .toInstant();
        assertEquals(renewed.occurredAt().atZone(ZoneOffset.UTC).plusMonths(12).toInstant(), expiry);
    }

    @Test
    void 만료되거나_취소된_신청을_재활성화하면_새_완료로_구분한다() {
        long userId = createUser();
        service.record(request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "member@example.com", null), userId);
        jdbcTemplate.update("""
                UPDATE notification_subscriptions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE user_id = ?
                """, userId);
        assertEquals(NotificationInterestOutcome.ACTIVATED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CONFIRMED, "11", "member@example.com", null), userId).outcome());
        assertEquals(NotificationInterestOutcome.CANCELLED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CANCELLED, "11", null, null), userId).outcome());
        assertEquals(NotificationInterestOutcome.ACTIVATED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CONFIRMED, "11", "member@example.com", null), userId).outcome());
    }

    @Test
    void 비회원의_반복_확인과_취소는_실제_상태변화와_구분한다() {
        UUID clientId = UUID.randomUUID();
        assertEquals(NotificationInterestOutcome.ACTIVATED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CONFIRMED, "11", "guest@example.com", clientId), null).outcome());
        assertEquals(NotificationInterestOutcome.ALREADY_ACTIVE, service.record(request(UUID.randomUUID(),
                NotificationEventType.CONFIRMED, "11", "guest@example.com", clientId), null).outcome());
        assertEquals(NotificationInterestOutcome.CANCELLED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CANCELLED, "11", null, clientId), null).outcome());
        assertEquals(NotificationInterestOutcome.UNCHANGED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CANCELLED, "11", null, clientId), null).outcome());
    }

    @Test
    void 이메일_조회_후_마지막_신청이_취소되면_회원_클릭을_완료로_처리하지_않는다() {
        long userId = createUser();
        service.record(request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "member@example.com", null), userId);
        assertTrue(subscriptions.hasEmail(userId));
        service.record(request(UUID.randomUUID(), NotificationEventType.CANCELLED, "11", null, null), userId);

        assertEquals(NotificationInterestOutcome.NOT_ACTIVATED,
                subscriptions.activate(userId, NotificationTargetType.REGION, "11", Instant.now()));
        assertTrue(service.findForUser(userId).targets().isEmpty());
    }

    @Test
    void 이메일_조회_후_마지막_신청이_취소되면_비회원_클릭도_완료로_처리하지_않는다() {
        UUID clientId = UUID.randomUUID();
        service.record(request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId), null);
        assertTrue(guestSubscriptions.hasEmail(clientId));
        service.record(request(UUID.randomUUID(), NotificationEventType.CANCELLED, "11", null, clientId), null);

        assertEquals(NotificationInterestOutcome.NOT_ACTIVATED,
                guestSubscriptions.activate(clientId, NotificationTargetType.REGION, "11", Instant.now()));
        assertTrue(service.findForClient(clientId).targets().isEmpty());
    }

    @Test
    void 응답_유실_재시도는_원래_결과와_시각을_그대로_반환한다() {
        UUID clientId = UUID.randomUUID();
        NotificationInterestRequest request = request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId);
        NotificationInterestResponse original = service.record(request, null);
        service.record(request(UUID.randomUUID(), NotificationEventType.CANCELLED, "11", null, clientId), null);

        assertEquals(original, service.record(request, null));
        assertTrue(service.findForClient(clientId).targets().isEmpty());
        assertEquals(1, eventCount(request.eventId()));
    }

    @Test
    void 대상이_제거된_뒤에도_완료_요청의_재시도는_원래_결과를_반환한다() {
        long complexId = createComplex();
        NotificationInterestRequest request = new NotificationInterestRequest(UUID.randomUUID(), SESSION_ID,
                NotificationEventType.CONFIRMED, NotificationEventSource.COMPLEX_DETAIL,
                NotificationTargetType.COMPLEX, Long.toString(complexId), "guest@example.com", UUID.randomUUID());
        NotificationInterestResponse original = service.record(request, null);
        jdbcTemplate.update("DELETE FROM housing_complexes WHERE id = ?", complexId);

        assertEquals(original, service.record(request, null));
    }

    @Test
    void 같은_이벤트의_대상과_명령과_이메일과_주체_변경을_거부한다() {
        UUID eventId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        NotificationInterestRequest original = request(eventId, NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId);
        service.record(original, null);
        List<NotificationInterestRequest> changed = List.of(
                request(eventId, NotificationEventType.CONFIRMED, "11680", "guest@example.com", clientId),
                request(eventId, NotificationEventType.CLICKED, "11", null, clientId),
                request(eventId, NotificationEventType.CONFIRMED, "11", "changed@example.com", clientId),
                request(eventId, NotificationEventType.CONFIRMED, "11", "guest@example.com", UUID.randomUUID()));

        for (NotificationInterestRequest request : changed) {
            assertThrows(NotificationInterestConflictException.class, () -> service.record(request, null));
        }
        assertThrows(NotificationInterestConflictException.class, () -> service.record(original, 90000099L));
        assertEquals(1, eventCount(eventId));
    }

    @Test
    void 충돌은_HTTP_409로_반환한다() throws Exception {
        UUID eventId = UUID.randomUUID();
        service.record(request(eventId, NotificationEventType.EXPOSED, "11", null, null), null);

        mockMvc.perform(post("/api/v1/notification-interest-events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                                request(eventId, NotificationEventType.CLICKED, "11", null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_INTEREST_CONFLICT"));
    }

    @Test
    void 과거_이벤트는_성공을_추정하거나_신청을_다시_실행하지_않는다() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-30T00:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO notification_interest_events
                    (event_id, session_id, event_type, source, target_type, target_id, created_at)
                VALUES (?, ?, 'CONFIRMED', 'REGION_SEARCH', 'REGION', '11', ?)
                """, eventId, SESSION_ID, Timestamp.from(occurredAt));
        UUID clientId = UUID.randomUUID();

        NotificationInterestResponse response = service.record(request(eventId, NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId), null);

        assertEquals(NotificationInterestOutcome.UNKNOWN, response.outcome());
        assertEquals(occurredAt, response.occurredAt());
        assertTrue(service.findForClient(clientId).targets().isEmpty());
    }

    @Test
    void 노출과_거절과_이메일없는_확인은_신청완료가_아니다() {
        assertEquals(NotificationInterestOutcome.OBSERVED, service.record(request(UUID.randomUUID(),
                NotificationEventType.EXPOSED, "11", null, null), null).outcome());
        assertEquals(NotificationInterestOutcome.OBSERVED, service.record(request(UUID.randomUUID(),
                NotificationEventType.DECLINED, "11", null, null), null).outcome());
        assertEquals(NotificationInterestOutcome.NOT_ACTIVATED, service.record(request(UUID.randomUUID(),
                NotificationEventType.CONFIRMED, "11", null, UUID.randomUUID()), null).outcome());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 신청_저장이_실패하면_이벤트_예약도_함께_롤백된다() {
        NotificationInterestRequest request = request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "99999", "member@example.com", null);

        assertThrows(InvalidNotificationInterestException.class, () -> service.record(request, null));

        assertEquals(0, eventCount(request.eventId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 결과_저장이_실패하면_이미_변경한_신청과_이벤트도_롤백된다() {
        UUID clientId = UUID.randomUUID();
        NotificationInterestRequest request = request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId);
        jdbcTemplate.execute("""
                ALTER TABLE notification_interest_events ADD CONSTRAINT outcome_test_failure
                CHECK (event_id <> '%s' OR outcome <> 'ACTIVATED')
                """.formatted(request.eventId()));
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.record(request, null));
            assertEquals(0, eventCount(request.eventId()));
            assertTrue(service.findForClient(clientId).targets().isEmpty());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_guest_email_preferences WHERE client_id = ?",
                    Integer.class, clientId));
        } finally {
            jdbcTemplate.execute("ALTER TABLE notification_interest_events DROP CONSTRAINT outcome_test_failure");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시에_재전송된_동일_이벤트는_같은_한번의_결과를_반환한다() throws Exception {
        UUID clientId = UUID.randomUUID();
        NotificationInterestRequest request = request(UUID.randomUUID(), NotificationEventType.CONFIRMED,
                "11", "guest@example.com", clientId);
        try {
            List<NotificationInterestResponse> results = race(() -> service.record(request, null));
            assertEquals(NotificationInterestOutcome.ACTIVATED, results.getFirst().outcome());
            assertEquals(results.getFirst(), results.getLast());
            assertEquals(1, eventCount(request.eventId()));
        } finally {
            cleanupGuest(clientId);
            jdbcTemplate.update("DELETE FROM notification_interest_events WHERE event_id = ?", request.eventId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 비회원의_동시_최초_활성화는_한_요청만_새_신청이다() throws Exception {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences (client_id, email, updated_at)
                VALUES (?, 'guest@example.com', CURRENT_TIMESTAMP)
                """, clientId);
        try {
            List<NotificationInterestOutcome> outcomes = race(() -> transaction().execute(status ->
                    guestSubscriptions.activate(clientId, NotificationTargetType.REGION, "11", Instant.now())));
            assertEquals(1, outcomes.stream().filter(NotificationInterestOutcome.ACTIVATED::equals).count());
            assertEquals(1, outcomes.stream().filter(NotificationInterestOutcome.ALREADY_ACTIVE::equals).count());
        } finally {
            cleanupGuest(clientId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 회원의_동시_재활성화도_한_요청만_새_신청이다() throws Exception {
        long userId = createUser();
        jdbcTemplate.update("""
                INSERT INTO notification_email_preferences (user_id, email, updated_at)
                VALUES (?, 'member@example.com', CURRENT_TIMESTAMP)
                """, userId);
        transaction().executeWithoutResult(status -> subscriptions.activate(
                userId, NotificationTargetType.REGION, "11", Instant.now()));
        jdbcTemplate.update("UPDATE notification_subscriptions SET active = false WHERE user_id = ?", userId);
        try {
            List<NotificationInterestOutcome> outcomes = race(() -> transaction().execute(status ->
                    subscriptions.activate(userId, NotificationTargetType.REGION, "11", Instant.now())));
            assertEquals(1, outcomes.stream().filter(NotificationInterestOutcome.ACTIVATED::equals).count());
            assertEquals(1, outcomes.stream().filter(NotificationInterestOutcome.ALREADY_ACTIVE::equals).count());
        } finally {
            jdbcTemplate.update("DELETE FROM notification_subscriptions WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification_email_preferences WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private NotificationInterestRequest request(UUID eventId, NotificationEventType type, String targetId,
            String email, UUID clientId) {
        return new NotificationInterestRequest(eventId, SESSION_ID, type, NotificationEventSource.REGION_SEARCH,
                NotificationTargetType.REGION, targetId, email, clientId);
    }

    private long createUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login_identifier, created_at) VALUES (?, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, UUID.randomUUID().toString());
    }

    private long createComplex() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO housing_complexes
                    (name, source_complex_identifier, supply_type, total_household_count, provider, parking_space_count,
                     road_address, pnu, legal_dong_code, province_code, city_county_district_code, latitude, longitude)
                VALUES ('계측 테스트 단지', ?, '행복주택', 1, 'LH', 1,
                    '테스트 주소', '1168010100100010000', '1168010100', '11', '11680', 37.5, 127.0) RETURNING id
                """, Long.class, UUID.randomUUID().toString());
    }

    private int eventCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_interest_events WHERE event_id = ?",
                Integer.class, eventId);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void cleanupGuest(UUID clientId) {
        jdbcTemplate.update("DELETE FROM notification_guest_subscriptions WHERE client_id = ?", clientId);
        jdbcTemplate.update("DELETE FROM notification_guest_email_preferences WHERE client_id = ?", clientId);
    }

    private <T> List<T> race(Callable<T> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> synchronizedOperation = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return operation.call();
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(synchronizedOperation);
            var second = executor.submit(synchronizedOperation);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
