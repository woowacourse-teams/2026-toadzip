package com.toadzip.backend.privacy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.toadzip.backend.privacy.service.PrivacyNotificationRetentionService;
import com.toadzip.backend.privacy.service.PrivacyRetentionService;
import com.toadzip.backend.privacy.service.UserRegistrationRetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class PrivacyRetentionConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PrivacyNoticeCatalog notices;
    private final UUID lockedConsent = UUID.randomUUID();
    private final UUID expiredConsent = UUID.randomUUID();
    private final UUID currentConsent = UUID.randomUUID();
    private final UUID expiredEvent = UUID.randomUUID();
    private final UUID currentEvent = UUID.randomUUID();
    private long member;
    private long orphan;

    @BeforeEach
    void setUp() {
        member = jdbc.queryForObject("""
                INSERT INTO users(login_identifier,created_at) VALUES (?,?) RETURNING id
                """, Long.class, "retention-concurrency:" + UUID.randomUUID(), Timestamp.from(NOW));
        orphan = -member * 1000;
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM privacy_analytics_consents WHERE id IN (?,?,?)",
                lockedConsent, expiredConsent, currentConsent);
        jdbc.update("DELETE FROM privacy_notification_events WHERE event_id IN (?,?)", expiredEvent, currentEvent);
        for (String table : List.of("privacy_notification_notices", "privacy_notification_receipts",
                "privacy_notification_states", "privacy_registration_notices")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ? OR user_id BETWEEN ? AND ?",
                    member, orphan - 501, orphan);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", member);
    }

    @Test
    void 두_동의_정리_인스턴스는_잠긴_비회원만_건너뛰고_현재_회원의_상태를_보존한다() {
        guest(lockedConsent);
        guest(expiredConsent);
        jdbc.update("""
                INSERT INTO privacy_analytics_consents(id,user_id,decision,notice_version,scope_version,
                    decided_at,revision,created_at,updated_at)
                VALUES (?,?,'DENIED','fixture','fixture',?,7,?,?)
                """, currentConsent, member, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        String before = currentConsentSnapshot();
        Worker blue = consentWorker();
        Worker green = consentWorker();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM privacy_analytics_consents WHERE id = ? FOR UPDATE", lockedConsent);
            runTogether(blue.run(), green.run());
            assertEquals(1, count("privacy_analytics_consents", "id = ?", lockedConsent));
            assertEquals(0, count("privacy_analytics_consents", "id = ?", expiredConsent));
            assertEquals(1.0, deleted(blue, green, "consent"));
        });
        runTogether(blue.run(), green.run());

        assertEquals(0, count("privacy_analytics_consents", "id IN (?,?)", lockedConsent, expiredConsent));
        assertEquals(2.0, deleted(blue, green, "consent"));
        assertEquals(before, currentConsentSnapshot());
        assertEquals(1, count("users", "id = ?", member));
    }

    @Test
    void 두_알림_개인정보_정리_인스턴스는_잠긴_회원과_유효기록을_보존하고_만료와_고아만_파기한다() {
        jdbc.update("""
                INSERT INTO privacy_notification_states(user_id,revision,updated_at) VALUES (?,7,?),(?,3,?)
                """, member, Timestamp.from(NOW), orphan, Timestamp.from(NOW));
        notice(member, "expired", NOW.minusSeconds(1));
        notice(member, "current", NOW.plusSeconds(3600));
        notice(orphan, "orphan", NOW.plusSeconds(3600));
        receipt(member, "expired", NOW.minusSeconds(1));
        receipt(member, "current", NOW.plusSeconds(3600));
        receipt(orphan, "orphan", NOW.plusSeconds(3600));
        event(expiredEvent, NOW.minusSeconds(1));
        event(currentEvent, NOW.plusSeconds(3600));
        Worker blue = notificationWorker();
        Worker green = notificationWorker();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", member);
            runTogether(blue.run(), green.run());
            assertEquals(2, count("privacy_notification_notices", "user_id = ?", member));
            assertEquals(2, count("privacy_notification_receipts", "user_id = ?", member));
            assertEquals(0, count("privacy_notification_states", "user_id = ?", orphan));
            assertEquals(4.0, deleted(blue, green, "notification"));
        });
        runTogether(blue.run(), green.run());

        assertEquals(1, count("privacy_notification_notices", "user_id = ? AND target_id = 'current'", member));
        assertEquals(1, count("privacy_notification_notices", "user_id = ?", member));
        assertEquals(1, count("privacy_notification_receipts", "user_id = ? AND target_id = 'current'", member));
        assertEquals(1, count("privacy_notification_receipts", "user_id = ?", member));
        assertEquals(1, count("privacy_notification_events", "event_id = ?", currentEvent));
        assertEquals(0, count("privacy_notification_events", "event_id = ?", expiredEvent));
        assertEquals(7L, jdbc.queryForObject(
                "SELECT revision FROM privacy_notification_states WHERE user_id = ?", Long.class, member));
        assertEquals(6.0, deleted(blue, green, "notification"));
    }

    @Test
    void 두_가입고지_정리_인스턴스는_잠긴_고아를_재시도하고_회원고지는_보존한다() {
        jdbc.update("""
                INSERT INTO privacy_registration_notices(user_id,policy_version,recorded_at)
                VALUES (?,'fixture',?)
                """, member, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO privacy_registration_notices(user_id,policy_version,recorded_at)
                SELECT ? - number,'fixture',? FROM generate_series(1,501) number
                """, orphan, Timestamp.from(NOW));
        Worker blue = registrationWorker();
        Worker green = registrationWorker();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT user_id FROM privacy_registration_notices WHERE user_id = ? FOR UPDATE",
                    orphan - 1);
            runTogether(blue.run(), green.run());
            assertEquals(1, count("privacy_registration_notices", "user_id BETWEEN ? AND ?", orphan - 501, orphan));
            assertEquals(500.0, deleted(blue, green, "registration-notice"));
        });
        runTogether(blue.run(), green.run());

        assertEquals(0, count("privacy_registration_notices", "user_id BETWEEN ? AND ?", orphan - 501, orphan));
        assertEquals(1, count("privacy_registration_notices", "user_id = ?", member));
        assertEquals(1, count("users", "id = ?", member));
        assertEquals(501.0, deleted(blue, green, "registration-notice"));
    }

    private Worker consentWorker() {
        var registry = new SimpleMeterRegistry();
        var service = new PrivacyRetentionService(new PrivacyRetentionRepository(jdbc), notices, CLOCK,
                transactionManager, registry);
        return new Worker(service::purgeExpiredData, registry);
    }

    private Worker notificationWorker() {
        var registry = new SimpleMeterRegistry();
        var service = new PrivacyNotificationRetentionService(new PrivacyNotificationRetentionRepository(jdbc),
                CLOCK, transactionManager, registry);
        return new Worker(service::purgeExpiredData, registry);
    }

    private Worker registrationWorker() {
        var registry = new SimpleMeterRegistry();
        var service = new UserRegistrationRetentionService(new UserRegistrationNoticeRepository(jdbc),
                transactionManager, CLOCK, registry);
        return new Worker(service::purge, registry);
    }

    private void runTogether(Runnable blue, Runnable green) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                await(start);
                blue.run();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                await(start);
                green.run();
            });
            await(ready);
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 정리 작업이 중단되었습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("잠긴 행을 건너뛰는 동시 정리 작업이 실패했습니다.", exception);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 정리 시작을 기다리다 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private double deleted(Worker blue, Worker green, String job) {
        return blue.registry().get("privacy.retention.deleted.total").tag("job", job).counter().count()
                + green.registry().get("privacy.retention.deleted.total").tag("job", job).counter().count();
    }

    private long count(String table, String condition, Object... values) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + condition, Long.class, values);
    }

    private String currentConsentSnapshot() {
        return jdbc.queryForObject("SELECT to_jsonb(c)::text FROM privacy_analytics_consents c WHERE id = ?",
                String.class, currentConsent);
    }

    private void guest(UUID id) {
        jdbc.update("""
                INSERT INTO privacy_analytics_consents(id,guest_token_hash,decision,revision,expires_at,
                    created_at,updated_at)
                VALUES (?,?,'UNSET',0,?,?,?)
                """, id, UUID.randomUUID().toString(), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(60)));
    }

    private void notice(long userId, String target, Instant purgeAfter) {
        jdbc.update("""
                INSERT INTO privacy_notification_notices(user_id,target_type,target_id,notice_version,
                    requested_at,expires_at,purge_after) VALUES (?,'REGION',?,'fixture',?,?,?)
                """, userId, target, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW),
                Timestamp.from(purgeAfter));
    }

    private void receipt(long userId, String target, Instant purgeAfter) {
        jdbc.update("""
                INSERT INTO privacy_notification_receipts(event_id,user_id,source,event_type,target_type,target_id,
                    request_fingerprint,outcome,created_at,settings_revision,notice_version,purge_after)
                VALUES (?,?,'REGION_SEARCH','ACTIVATED','REGION',?,'fixture','ACTIVATED',?,7,'fixture',?)
                """, UUID.randomUUID(), userId, target, Timestamp.from(NOW), Timestamp.from(purgeAfter));
    }

    private void event(UUID eventId, Instant purgeAfter) {
        jdbc.update("""
                INSERT INTO privacy_notification_events(event_id,session_id,event_type,source,target_type,target_id,
                    created_at,request_fingerprint,outcome,purge_after)
                VALUES (?,?,'CLICKED','REGION_SEARCH','REGION','11',?,'fixture','OBSERVED',?)
                """, eventId, UUID.randomUUID(), Timestamp.from(NOW), Timestamp.from(purgeAfter));
    }

    private record Worker(Runnable run, SimpleMeterRegistry registry) {
    }
}
