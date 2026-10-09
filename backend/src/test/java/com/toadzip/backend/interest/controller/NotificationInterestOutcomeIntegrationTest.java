package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationSettingsRequest;
import com.toadzip.backend.interest.dto.NotificationSettingsResponse;
import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import com.toadzip.backend.interest.exception.NotificationSettingsConflictException;
import com.toadzip.backend.privacy.repository.PrivacyNotificationRetentionRepository;
import com.toadzip.backend.interest.service.NotificationSettingsService;
import com.toadzip.backend.interest.repository.NotificationSubscriptionRepository;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(NotificationInterestOutcomeIntegrationTest.ClockConfiguration.class)
class NotificationInterestOutcomeIntegrationTest {

    @Autowired private NotificationSettingsService service;
    @Autowired private NotificationSubscriptionRepository subscriptions;
    @Autowired private PrivacyNotificationRetentionRepository retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private NotificationClock clock;
    private long userId;

    @BeforeEach
    void member() {
        clock.set(Instant.parse("2026-10-09T09:00:00Z"));
        userId = jdbc.queryForObject("""
                INSERT INTO users(login_identifier, email, created_at) VALUES (?, 'retained@example.com', ?)
                RETURNING id
                """, Long.class, UUID.randomUUID().toString(), Timestamp.from(clock.instant()));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM privacy_notification_receipts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM privacy_notification_notices WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM privacy_notification_states WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notification_subscriptions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notification_email_preferences WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 이미_활성인_설정과_명령_재시도는_신청기간을_연장하지_않는다() {
        NotificationSettingsRequest original = request(0, NotificationEventType.CONFIRMED, "11");
        NotificationSettingsResponse first = service.change(userId, original);
        clock.set(clock.instant().plusSeconds(3600));
        NotificationSettingsResponse already = service.change(
                userId, request(1, NotificationEventType.CONFIRMED, "11"));
        assertEquals(NotificationInterestOutcome.ALREADY_ACTIVE, already.outcome());
        assertEquals(first.currentTarget(), already.currentTarget());
        NotificationSettingsResponse retried = service.change(userId, original);
        assertEquals(first.outcome(), retried.outcome());
        assertEquals(first.occurredAt(), retried.occurredAt());
        assertEquals(2, retried.settingsRevision());
        assertEquals(first.currentTarget(), retried.currentTarget());
    }

    @Test
    void 취소후_이전_신청_재전송은_원래영수증과_현재취소를_함께_반환한다() {
        NotificationSettingsRequest original = request(0, NotificationEventType.CONFIRMED, "11");
        service.change(userId, original);
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        NotificationSettingsResponse retried = service.change(userId, original);
        assertEquals(NotificationInterestOutcome.ACTIVATED, retried.outcome());
        assertEquals(2, retried.settingsRevision());
        assertFalse(retried.currentTarget().active());
        assertTrue(service.current(userId).targets().isEmpty());
    }

    @Test
    void 같은_명령의_다른본문과_오래된revision은_별도로_거절한다() {
        NotificationSettingsRequest original = request(0, NotificationEventType.CONFIRMED, "11");
        service.change(userId, original);
        NotificationSettingsRequest changed = new NotificationSettingsRequest(
                original.eventId(), original.expectedUserId(),
                original.expectedSettingsRevision(), NotificationEventType.CANCELLED, original.source(),
                original.targetType(), original.targetId(), null);
        assertThrows(NotificationInterestConflictException.class, () -> service.change(userId, changed));
        assertThrows(NotificationSettingsConflictException.class,
                () -> service.change(userId, request(0, NotificationEventType.CANCELLED, "11")));
        assertEquals(1, service.current(userId).settingsRevision());
    }

    @Test
    void 만료_정각부터_조회에서_제외하고_재신청은_새기간을_갖는다() {
        NotificationSettingsResponse first = service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        Instant expiry = first.currentTarget().expiresAt();
        clock.set(expiry.minusNanos(1000));
        assertEquals(1, service.current(userId).targets().size());
        clock.set(expiry);
        assertTrue(service.current(userId).targets().isEmpty());
        NotificationSettingsResponse renewed = service.change(
                userId, request(1, NotificationEventType.CONFIRMED, "11"));
        assertEquals(NotificationInterestOutcome.ACTIVATED, renewed.outcome());
        assertEquals(expiry, renewed.currentTarget().requestedAt());
        assertEquals(new PrivacyRetentionPolicy().notificationExpiresAt(expiry), renewed.currentTarget().expiresAt());
    }

    @Test
    void 취소_재시도는_파기시각을_미루지_않는다() {
        service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        clock.set(clock.instant().plusSeconds(100));
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        Instant due = purgeAfter();
        clock.set(clock.instant().plusSeconds(100));
        assertEquals(NotificationInterestOutcome.UNCHANGED,
                service.change(userId, request(2, NotificationEventType.CANCELLED, "11")).outcome());
        assertEquals(due, purgeAfter());
    }

    @Test
    void 만료된_설정_취소는_만료시각기준의_파기예정을_유지한다() {
        NotificationSettingsResponse first = service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        Instant due = purgeAfter();
        clock.set(first.currentTarget().expiresAt().plusSeconds(10));
        assertEquals(NotificationInterestOutcome.UNCHANGED,
                service.change(userId, request(1, NotificationEventType.CANCELLED, "11")).outcome());
        assertEquals(due, purgeAfter());
    }

    @Test
    void 삭제된_대상도_안내버전없이_SETTING에서_취소한다() {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (?, 'ANNOUNCEMENT','999999999',true,?,?)
                """, userId, Timestamp.from(now), Timestamp.from(now.plusSeconds(100)));
        NotificationSettingsRequest request = new NotificationSettingsRequest(UUID.randomUUID(), Long.toString(userId),
                0L, NotificationEventType.CANCELLED, NotificationEventSource.SETTING,
                NotificationTargetType.ANNOUNCEMENT, "999999999", null);
        assertEquals(NotificationInterestOutcome.CANCELLED, service.change(userId, request).outcome());
    }

    @Test
    void 상태와_영수증_뒤의revision저장이_실패하면_모두_롤백된다() {
        jdbc.execute("ALTER TABLE privacy_notification_states ADD CONSTRAINT test_notification_revision_failure "
                + "CHECK (user_id <> " + userId + " OR revision = 0)");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> service.change(userId, request(0, NotificationEventType.CONFIRMED, "11")));
            assertEquals(0, eventCount());
            assertTrue(service.current(userId).targets().isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE privacy_notification_states DROP CONSTRAINT test_notification_revision_failure");
        }
    }

    @Test
    void 동시_변경은_한명만_같은revision을_사용한다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> raceChange(ready, start, "11"));
            var second = executor.submit(() -> raceChange(ready, start, "26"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, eventCount());
        assertEquals(1, service.current(userId).settingsRevision());
    }

    @Test
    void 파기정각에_신규고지와_영수증만_삭제하고_기존설정과_revision은_남긴다() {
        NotificationSettingsRequest original = request(0, NotificationEventType.CONFIRMED, "11");
        service.change(userId, original);
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        Instant due = purgeAfter();
        clock.set(due.minusNanos(1000));
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(1, settingCount());
        clock.set(due);
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(0, settingCount());
        assertEquals(0, eventCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_subscriptions WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(2, service.current(userId).settingsRevision());
        assertThrows(NotificationSettingsConflictException.class, () -> service.change(userId, original));
        assertEquals("retained@example.com", jdbc.queryForObject("SELECT email FROM users WHERE id = ?",
                String.class, userId));
    }

    @Test
    void 재신청된_설정은_예전_파기기한으로_삭제하지_않는다() {
        service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        Instant oldDue = purgeAfter();
        clock.set(oldDue);
        service.change(userId, request(2, NotificationEventType.CONFIRMED, "11"));
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(1, settingCount());
        assertEquals(1, service.current(userId).targets().size());
    }

    @Test
    void 실패한_파기청크는_모두_롤백하고_다음실행에서_재시도한다() {
        service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        clock.set(purgeAfter());
        jdbc.execute("""
                CREATE FUNCTION test_notification_purge_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated retention failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_notification_purge_failure BEFORE DELETE ON privacy_notification_notices
                FOR EACH ROW EXECUTE FUNCTION test_notification_purge_failure()
                """);
        try {
            assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(
                    status -> retention.purgeChunk(clock.instant(), 500)));
            assertEquals(2, eventCount());
            assertEquals(1, settingCount());
        } finally {
            jdbc.execute("DROP TRIGGER test_notification_purge_failure ON privacy_notification_notices");
            jdbc.execute("DROP FUNCTION test_notification_purge_failure()");
        }
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(0, eventCount());
        assertEquals(0, settingCount());
    }

    @Test
    void 두_파기실행이_겹쳐도_안전하게_삭제한다() throws Exception {
        service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        clock.set(purgeAfter());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.execute(
                    status -> retention.purgeChunk(clock.instant(), 500)));
            var second = executor.submit(() -> transactions.execute(
                    status -> retention.purgeChunk(clock.instant(), 500)));
            assertEquals(3, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, eventCount());
        assertEquals(0, settingCount());
    }

    @Test
    void 재신청이_회원잠금을_보유하면_파기는_건너뛰고_새설정을_보존한다() throws Exception {
        service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        service.change(userId, request(1, NotificationEventType.CANCELLED, "11"));
        clock.set(purgeAfter());
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var renewal = executor.submit(() -> transactions.executeWithoutResult(status -> {
                service.change(userId, request(2, NotificationEventType.CONFIRMED, "11"));
                changed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertTrue(changed.await(10, TimeUnit.SECONDS));
                int deleted = transactions.execute(status -> retention.purgeChunk(clock.instant(), 500));
                assertEquals(0, deleted);
            } finally {
                release.countDown();
            }
            renewal.get(10, TimeUnit.SECONDS);
        }
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(1, service.current(userId).targets().size());
        assertEquals(3, service.current(userId).settingsRevision());
    }

    @Test
    void 업무이력을_90일후_삭제해도_활성설정의_신청안내는_남는다() {
        NotificationSettingsRequest original = request(0, NotificationEventType.CONFIRMED, "11");
        NotificationSettingsResponse first = service.change(userId, original);
        clock.set(clock.instant().plus(PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION));
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(0, eventCount());
        assertEquals(1, service.current(userId).targets().size());
        assertEquals(first.currentTarget().noticeVersion(),
                service.current(userId).targets().getFirst().noticeVersion());
        assertEquals(first.currentTarget().requestedAt(), service.current(userId).targets().getFirst().requestedAt());
        assertThrows(NotificationSettingsConflictException.class, () -> service.change(userId, original));
    }

    @Test
    void 한_청크는_500행까지만_삭제하고_남은대상을_다음청크에_처리한다() {
        Instant created = clock.instant().minus(PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION);
        jdbc.update("""
                INSERT INTO privacy_notification_receipts(event_id,event_type,source,target_type,target_id,created_at,
                    outcome,user_id,notice_version,settings_revision,request_fingerprint,purge_after)
                SELECT ('90000000-0000-4000-8000-' || lpad(sequence::text,12,'0'))::uuid,
                    'CONFIRMED','SETTING','REGION','11',?,'ACTIVATED',?,'notification-2026-10-10-v1',sequence,'test',?
                FROM generate_series(1,501) AS sequence
                """, Timestamp.from(created), userId, Timestamp.from(clock.instant()));
        int first = transactions.execute(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(500, first);
        assertEquals(1, eventCount());
        int second = transactions.execute(status -> retention.purgeChunk(clock.instant(), 500));
        assertEquals(1, second);
        assertEquals(0, eventCount());
    }

    @Test
    void 고지메타를_제거해도_기존알림의_활성상태와_만료시각은_유지된다() {
        NotificationSettingsResponse first = service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        jdbc.update("DELETE FROM privacy_notification_notices WHERE user_id = ?", userId);

        var current = service.current(userId);

        assertEquals(1, current.targets().size());
        assertEquals(first.currentTarget().expiresAt(), current.targets().getFirst().expiresAt());
        assertEquals(1, current.settingsRevision());
        assertNull(current.targets().getFirst().noticeVersion());
        assertNull(current.targets().getFirst().requestedAt());
    }

    @Test
    void 기존업무의_재신청은_고지메타와_무관하게_실제만료시각을_갱신한다() {
        NotificationSettingsResponse first = service.change(userId, request(0, NotificationEventType.CONFIRMED, "11"));
        Instant originalPurgeAfter = purgeAfter();
        clock.set(first.currentTarget().expiresAt().plusSeconds(1));
        subscriptions.activateForMember(userId, NotificationTargetType.REGION, "11", clock.instant());
        Instant businessExpiry = jdbc.queryForObject("""
                SELECT expires_at FROM notification_subscriptions WHERE user_id = ?
                """, Timestamp.class, userId).toInstant();

        assertEquals(businessExpiry, service.current(userId).targets().getFirst().expiresAt());
        assertEquals(originalPurgeAfter, purgeAfter());
        clock.set(originalPurgeAfter);
        transactions.executeWithoutResult(status -> retention.purgeChunk(clock.instant(), 500));

        assertEquals(0, settingCount());
        assertEquals(1, service.current(userId).targets().size());
        assertEquals(businessExpiry, service.current(userId).targets().getFirst().expiresAt());
    }

    private int raceChange(CountDownLatch ready, CountDownLatch start, String target) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            service.change(userId, request(0, NotificationEventType.CONFIRMED, target));
            return 1;
        } catch (NotificationSettingsConflictException exception) {
            return 0;
        }
    }

    private NotificationSettingsRequest request(long revision, NotificationEventType type, String target) {
        String notice = null;
        if (type == NotificationEventType.CONFIRMED) {
            notice = "notification-2026-10-10-v1";
        }
        return new NotificationSettingsRequest(UUID.randomUUID(), Long.toString(userId), revision, type,
                NotificationEventSource.SETTING, NotificationTargetType.REGION, target, notice);
    }

    private Instant purgeAfter() {
        return jdbc.queryForObject("SELECT purge_after FROM privacy_notification_notices WHERE user_id = ?",
                Timestamp.class, userId).toInstant();
    }

    private int settingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM privacy_notification_notices WHERE user_id = ?",
                Integer.class, userId);
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM privacy_notification_receipts WHERE user_id = ?",
                Integer.class, userId);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean @Primary NotificationClock notificationClock() {
            return new NotificationClock();
        }
    }

    static class NotificationClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.EPOCH);
        void set(Instant value) { current.set(value); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }
}
