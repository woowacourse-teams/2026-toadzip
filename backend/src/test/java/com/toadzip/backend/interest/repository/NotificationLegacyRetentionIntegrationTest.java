package com.toadzip.backend.interest.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class NotificationLegacyRetentionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");

    @Autowired private NotificationRetentionRepository retention;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactions;
    private final List<UUID> owners = new ArrayList<>();
    private final List<UUID> requests = new ArrayList<>();
    private final List<Long> members = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID request : requests) {
            jdbcTemplate.update("DELETE FROM notification_guest_cancellation_requests WHERE id = ?", request);
        }
        for (UUID owner : owners) {
            jdbcTemplate.update("DELETE FROM notification_guest_subscriptions WHERE client_id = ?", owner);
            jdbcTemplate.update("DELETE FROM notification_guest_email_preferences WHERE client_id = ?", owner);
        }
        for (Long member : members) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", member);
        }
    }

    @Test
    void 비회원_만료자료를_파기하고_회원이메일은_보존한다() {
        String email = email();
        UUID owner = preference(email);
        subscription(owner, true, NOW);
        cancellation(email, NOW.minusSeconds(60));
        long member = jdbcTemplate.queryForObject("""
                INSERT INTO users(login_identifier, email, created_at) VALUES (?, ?, ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), email, Timestamp.from(NOW));
        members.add(member);

        assertEquals(0, purge(NOW.minusNanos(1000)));
        assertEquals(3, retention.backlog(NOW).count());
        assertEquals(3, purge(NOW));
        assertEquals(0, guestRows());
        assertEquals(0, retention.backlog(NOW).count());
        assertEquals(email, jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, member));
    }

    @Test
    void 비활성_비회원_설정은_미래_만료일이_남아도_기존_파기기준을_따른다() {
        String email = email();
        UUID owner = preference(email);
        subscription(owner, false, NOW.plus(Duration.ofDays(100)));
        cancellation(email, NOW.minusSeconds(60));

        assertEquals(3, retention.backlog(NOW).count());
        assertEquals(3, purge(NOW));
        assertEquals(0, guestRows());
    }

    @Test
    void 같은_이메일의_유효한_다른_비회원_구독과_취소경로는_보존한다() {
        String email = email();
        UUID expiredOwner = preference(email);
        subscription(expiredOwner, true, NOW.minusSeconds(1));
        UUID activeOwner = preference(email.toUpperCase(Locale.ROOT));
        subscription(activeOwner, true, NOW.plusSeconds(3600));
        UUID request = cancellation(email, NOW.minusSeconds(60));

        assertEquals(2, retention.backlog(NOW).count());
        assertEquals(2, purge(NOW));
        assertEquals(3, guestRows());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_guest_subscriptions WHERE client_id = ?",
                Integer.class, activeOwner));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_guest_cancellation_requests WHERE id = ?", Integer.class, request));
    }

    @Test
    void 유효한_비회원_설정이_있어도_취소요청은_30일_정각에_파기한다() {
        String email = email();
        UUID owner = preference(email);
        subscription(owner, true, NOW.plusSeconds(3600));
        cancellation(email, NOW.minus(PrivacyRetentionPolicy.LEGACY_GUEST_CANCELLATION_RETENTION));

        assertEquals(0, purge(NOW.minusNanos(1000)));
        assertEquals(1, retention.backlog(NOW).count());
        assertEquals(1, purge(NOW));
        assertEquals(2, guestRows());
        assertEquals(0, retention.backlog(NOW).count());
    }

    @Test
    void 구독이_없는_이메일과_연결이_없는_취소요청도_파기한다() {
        preference(email());
        cancellation(email(), NOW.minusSeconds(60));

        assertEquals(2, retention.backlog(NOW).count());
        assertEquals(2, purge(NOW));
        assertEquals(0, guestRows());
    }

    @Test
    void 비회원_자식과_이메일과_요청은_모두_500행_예산에_포함한다() {
        String email = email();
        UUID owner = preference(email);
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                SELECT ?, 'REGION', number::text, true, ?, ? FROM generate_series(1, 501) AS number
                """, owner, Timestamp.from(NOW.minus(Duration.ofDays(366))), Timestamp.from(NOW.minusSeconds(60)));
        cancellation(email, NOW.minusSeconds(120));

        assertEquals(503, retention.backlog(NOW).count());
        assertEquals(60, retention.backlog(NOW).oldestOverdueSeconds());
        assertEquals(500, purge(NOW));
        assertEquals(3, guestRows());
        assertEquals(3, retention.backlog(NOW).count());
        assertEquals(3, purge(NOW));
        assertEquals(0, guestRows());
        assertEquals(0, retention.backlog(NOW).count());
    }

    @Test
    void 잠긴_비회원은_건너뛰고_갱신완료후_유효성을_재확인한다() throws Exception {
        String email = email();
        UUID owner = preference(email);
        subscription(owner, true, NOW.minusSeconds(60));
        cancellation(email, NOW.minusSeconds(120));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var renewal = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("""
                        SELECT client_id FROM notification_guest_email_preferences WHERE client_id = ? FOR UPDATE
                        """, UUID.class, owner);
                locked.countDown();
                await(release);
                jdbcTemplate.update("UPDATE notification_guest_subscriptions SET expires_at = ? WHERE client_id = ?",
                        Timestamp.from(NOW.plusSeconds(3600)), owner);
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                assertEquals(0, purge(NOW));
                assertEquals(3, retention.backlog(NOW).count());
            } finally {
                release.countDown();
            }
            renewal.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, purge(NOW));
        assertEquals(3, guestRows());
        assertEquals(0, retention.backlog(NOW).count());
    }

    @Test
    void 잠긴_취소요청은_다음청크에서_다시_파기하고_연체지표에_포함한다() throws Exception {
        String email = email();
        UUID owner = preference(email);
        subscription(owner, true, NOW.plusSeconds(3600));
        UUID request = cancellation(email,
                NOW.minus(PrivacyRetentionPolicy.LEGACY_GUEST_CANCELLATION_RETENTION).minusSeconds(1801));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inUse = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("""
                        SELECT id FROM notification_guest_cancellation_requests WHERE id = ? FOR UPDATE
                        """, UUID.class, request);
                locked.countDown();
                await(release);
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                assertEquals(0, purge(NOW));
                assertEquals(1, retention.backlog(NOW).count());
                assertEquals(1801, retention.backlog(NOW).oldestOverdueSeconds());
            } finally {
                release.countDown();
            }
            inUse.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, purge(NOW));
        assertEquals(2, guestRows());
    }

    private UUID preference(String email) {
        UUID owner = UUID.randomUUID();
        owners.add(owner);
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences(client_id, email, updated_at) VALUES (?, ?, ?)
                """, owner, email, Timestamp.from(NOW.minus(Duration.ofDays(366))));
        return owner;
    }

    private void subscription(UUID owner, boolean active, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, 'REGION', '11', ?, ?, ?)
                """, owner, active, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(expiresAt));
    }

    private UUID cancellation(String email, Instant requestedAt) {
        UUID request = UUID.randomUUID();
        requests.add(request);
        jdbcTemplate.update("""
                INSERT INTO notification_guest_cancellation_requests(id, email, requested_at, failed_attempts)
                VALUES (?, ?, ?, 0)
                """, request, email, Timestamp.from(requestedAt));
        return request;
    }

    private int purge(Instant now) {
        return transactions.execute(status -> retention.purgeChunk(now, 500));
    }

    private int guestRows() {
        int count = 0;
        for (UUID owner : owners) {
            count += jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_guest_email_preferences WHERE client_id = ?", Integer.class,
                    owner);
            count += jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_guest_subscriptions WHERE client_id = ?", Integer.class, owner);
        }
        for (UUID request : requests) {
            count += jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_guest_cancellation_requests WHERE id = ?",
                    Integer.class, request);
        }
        return count;
    }

    private String email() {
        return UUID.randomUUID() + "@example.com";
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock release timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
