package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationDeliveryRepository;
import com.toadzip.backend.notification.repository.NotificationPushSender;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class NotificationDeliveryIntegrationTest {
    private static final long MEMBER = 62001;
    @Autowired private NotificationMaterializationService materialization;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private NotificationAudienceRepository audience;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    private long announcementId;
    private long complexId;
    private long eventId;
    private UUID deviceId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var fixtures = new NotificationFixtures(entities, jdbc);
            var announcement = fixtures.announcement();
            var complex = fixtures.complex("11140", "1114010100");
            announcementId = announcement.getId();
            complexId = complex.getId();
            fixtures.link(announcement, complex);
            fixtures.user(MEMBER);
            fixtures.subscribe(MEMBER, "REGION", "11", NOW.minusSeconds(60));
            deviceId = fixtures.device(MEMBER);
            eventId = fixtures.event(announcement);
        });
        materialization.materializeNextPage(NOW, 200);
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notification_deliveries WHERE device_id=?", deviceId);
            jdbc.update("DELETE FROM user_notifications WHERE event_id=?", eventId);
            jdbc.update("DELETE FROM notification_outbox_events WHERE id=?", eventId);
            jdbc.update("DELETE FROM notification_push_devices WHERE id=?", deviceId);
            jdbc.update("DELETE FROM notification_subscriptions WHERE user_id=?", MEMBER);
            jdbc.update("DELETE FROM supply_rows WHERE announcement_id=?", announcementId);
            jdbc.update("DELETE FROM announcements WHERE id=?", announcementId);
            jdbc.update("DELETE FROM housing_complexes WHERE id=?", complexId);
            jdbc.update("DELETE FROM users WHERE id=?", MEMBER);
        });
    }

    @Test
    void FCM은_DB트랜잭션_밖에서_호출하고_성공해도_읽음으로_바꾸지_않는다() {
        NotificationPushSender sender = delivery -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(delivery.fid()).isEqualTo("fixture-" + deviceId);
            return new NotificationSendResult(Status.ACCEPTED, "projects/test/messages/1", null, null);
        };
        assertThat(service(sender).deliverAvailable(20)).isEqualTo(1);
        assertThat(status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM user_notifications WHERE event_id=?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    void 임시오류는_RetryAfter_뒤에_재시도한다() {
        service(delivery -> new NotificationSendResult(Status.RETRYABLE, null, "QUOTA_EXCEEDED", NOW.plusSeconds(300)))
                .deliverAvailable(20);
        assertThat(status()).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM notification_deliveries WHERE device_id=?",
                java.sql.Timestamp.class, deviceId).toInstant()).isEqualTo(NOW.plusSeconds(300));
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_deliveries WHERE device_id=?",
                Integer.class, deviceId)).isEqualTo(1);
    }

    @Test
    void 등록해제_응답은_기기를_끄고_대기작업을_건너뛴다() {
        service(delivery -> new NotificationSendResult(Status.INVALID_REGISTRATION, null, "UNREGISTERED", null))
                .deliverAvailable(20);
        assertThat(status()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices WHERE id=?", Boolean.class, deviceId))
                .isFalse();
    }

    @Test
    void 영구오류와_여덟번째_실패는_종료한다() {
        jdbc.update("UPDATE notification_deliveries SET attempts=7 WHERE device_id=?", deviceId);
        service(delivery -> new NotificationSendResult(Status.RETRYABLE, null, "TIMEOUT", null)).deliverAvailable(20);
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    void 구독이_해제되면_외부_발송없이_건너뛴다() {
        jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=?", MEMBER);
        service(delivery -> { throw new AssertionError("취소한 구독에 발송했습니다."); }).deliverAvailable(20);
        assertThat(status()).isEqualTo("SKIPPED");
    }

    @Test
    void 기기가_다른_계정에_연결되면_외부_발송없이_건너뛴다() {
        jdbc.update("UPDATE notification_push_devices SET binding_version=2 WHERE id=?", deviceId);
        service(delivery -> { throw new AssertionError("이전 바인딩에 발송했습니다."); }).deliverAvailable(20);
        assertThat(status()).isEqualTo("SKIPPED");
    }

    @Test
    void 만료_lease는_회수하지만_이전_토큰의_결과는_무시한다() {
        var transaction = new TransactionTemplate(manager);
        var first = transaction.execute(status -> deliveries.claimNext(NOW, java.time.Duration.ofSeconds(120)))
                .orElseThrow();
        assertThat(jdbc.queryForObject("SELECT lease_until FROM notification_deliveries WHERE id=?",
                java.sql.Timestamp.class, first.id()).toInstant()).isEqualTo(NOW.plusSeconds(120));
        assertThat(transaction.<java.util.Optional<com.toadzip.backend.notification.domain.ClaimedNotificationDelivery>>execute(status -> deliveries.claimNext(NOW.plusSeconds(119),
                java.time.Duration.ofSeconds(120)))).isEmpty();
        var recovered = transaction.execute(status -> deliveries.claimNext(NOW.plusSeconds(120),
                java.time.Duration.ofSeconds(120))).orElseThrow();
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        var accepted = new NotificationSendResult(Status.ACCEPTED, "projects/test/messages/recovery", null, null);
        assertThat(transaction.<Boolean>execute(status -> deliveries.recordResult(first, accepted, NOW.plusSeconds(121))))
                .isFalse();
        assertThat(transaction.<Boolean>execute(status -> deliveries.recordResult(recovered, accepted, NOW.plusSeconds(121))))
                .isTrue();
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    void 접수성공후_결과를_잃어도_보관함은_하나로_유지된다() {
        new TransactionTemplate(manager).execute(status -> deliveries.claimNext(NOW, java.time.Duration.ofSeconds(120)))
                .orElseThrow();
        // FCM 접수 뒤 프로세스 종료: DB 완료 갱신을 하지 않고 lease 복구로 재전송한다.
        var sender = (NotificationPushSender) delivery ->
                new NotificationSendResult(Status.ACCEPTED, "projects/test/messages/replayed", null, null);
        new NotificationDeliveryService(deliveries, audience, sender, manager,
                Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC)).deliverAvailable(20);
        assertThat(status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE event_id=?", Long.class, eventId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_deliveries WHERE device_id=?",
                Integer.class, deviceId)).isEqualTo(2);
    }

    @Test
    void 하루지난_작업과_영구오류는_더_전송하지_않는다() {
        service(delivery -> new NotificationSendResult(Status.PERMANENT_FAILURE, null, "INVALID_ARGUMENT", null))
                .deliverAvailable(20);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices WHERE id=?", Boolean.class, deviceId))
                .isTrue();
        jdbc.update("UPDATE notification_deliveries SET status='RETRY',next_attempt_at=? WHERE device_id=?",
                java.sql.Timestamp.from(NOW), deviceId);
        new NotificationDeliveryService(deliveries, audience,
                delivery -> { throw new AssertionError("만료된 작업을 발송했습니다."); }, manager,
                Clock.fixed(NOW.plusSeconds(86400), ZoneOffset.UTC)).deliverAvailable(20);
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    void 두_워커는_같은_발송_작업을_동시에_선점하지_않는다() throws Exception {
        var claimed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                var delivery = deliveries.claimNext(NOW, java.time.Duration.ofSeconds(120));
                claimed.countDown();
                try {
                    assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return delivery;
            }));
            try {
                assertThat(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> new TransactionTemplate(manager).execute(status ->
                        deliveries.claimNext(NOW, java.time.Duration.ofSeconds(120))));
                assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isPresent();
        }
    }

    private NotificationDeliveryService service(NotificationPushSender sender) {
        return new NotificationDeliveryService(deliveries, audience, sender, manager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE device_id=?", String.class, deviceId);
    }
}
