package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationDeliveryRepository;
import com.toadzip.backend.notification.repository.NotificationPushSender;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
class NotificationRegionBatchIntegrationTest {
    private static final long USER = 64001;
    @Autowired private NotificationMaterializationService materialization;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private NotificationAudienceRepository audience;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    private final List<Long> announcements = new ArrayList<>();
    private final List<Long> complexes = new ArrayList<>();
    private UUID device;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var f = new NotificationFixtures(entities, jdbc);
            f.user(USER);
            f.subscribe(USER, "REGION", "11", NOW.minusSeconds(60));
            device = f.device(USER);
        });
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notification_deliveries WHERE notification_id IN (SELECT id FROM user_notifications WHERE user_id IN (?,?))", USER, USER + 1);
            jdbc.update("DELETE FROM user_notifications WHERE user_id IN (?,?)", USER, USER + 1);
            for (long id : announcements) {
                jdbc.update("DELETE FROM notification_outbox_events WHERE announcement_id=?", id);
                jdbc.update("DELETE FROM supply_rows WHERE announcement_id=?", id);
                jdbc.update("DELETE FROM announcements WHERE id=?", id);
            }
            for (long id : complexes) jdbc.update("DELETE FROM housing_complexes WHERE id=?", id);
            jdbc.update("DELETE FROM notification_push_devices WHERE user_id IN (?,?)", USER, USER + 1);
            jdbc.update("DELETE FROM notification_subscriptions WHERE user_id IN (?,?)", USER, USER + 1);
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", USER, USER + 1);
        });
    }

    @Test
    void 같은_지역의_1분간_공고는_보관함에는_각각_남기고_푸시는_한번_보낸다() {
        announcement(NOW);
        announcement(NOW.plusSeconds(20));
        announcement(NOW.plusSeconds(59));
        var sent = new AtomicInteger();
        NotificationPushSender sender = value -> { sent.incrementAndGet(); return accepted(); };
        assertThat(service(NOW.plusSeconds(59), sender).deliverAvailable(20)).isZero();
        assertThat(service(NOW.plusSeconds(60), sender).deliverAvailable(20)).isOne();
        assertThat(sent).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE user_id=?", Long.class, USER)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE device_id=? AND status='SENT'", Long.class, device)).isEqualTo(3);
    }

    @Test
    void 묶음_재시도도_한번만_발송하고_삭제된_첫_공고가_다른_공고를_막지_않는다() {
        announcement(NOW);
        announcement(NOW.plusSeconds(20));
        assertThat(service(NOW.plusSeconds(60), value -> new NotificationSendResult(Status.RETRYABLE, null, "UNAVAILABLE", NOW.plusSeconds(300))).deliverAvailable(20)).isOne();
        jdbc.update("UPDATE user_notifications SET deleted_at=? WHERE user_id=? AND id=(SELECT min(id) FROM user_notifications WHERE user_id=?)", java.sql.Timestamp.from(NOW), USER, USER);
        var sent = new AtomicInteger();
        assertThat(service(NOW.plusSeconds(330), value -> { sent.incrementAndGet(); return accepted(); }).deliverAvailable(20)).isOne();
        assertThat(sent).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE device_id=? AND status='SKIPPED'", Long.class, device)).isOne();
    }

    @Test
    void 지역과_회원과_기기가_다르면_별도_푸시로_보낸다() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var f = new NotificationFixtures(entities, jdbc);
            jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=?", USER);
            f.subscribe(USER, "REGION", "11140", NOW.minusSeconds(60));
            f.subscribe(USER, "REGION", "11440", NOW.minusSeconds(60));
            f.device(USER);
            f.user(USER + 1);
            f.subscribe(USER + 1, "REGION", "11", NOW.minusSeconds(60));
            f.device(USER + 1);
        });
        announcement(NOW, "11140", "1114010100");
        announcement(NOW.plusSeconds(20), "11440", "1144010100");
        var sent = new ArrayList<ClaimedNotificationDelivery>();
        assertThat(service(NOW.plusSeconds(80), value -> { sent.add(value); return accepted(); })
                .deliverAvailable(20)).isEqualTo(5);
        assertThat(sent.stream().filter(value -> value.userId() == USER))
                .allSatisfy(value -> assertThat(value.members()).hasSize(1));
        assertThat(sent.stream().filter(value -> value.userId() == USER + 1))
                .singleElement().satisfies(value -> assertThat(value.members()).hasSize(2));
        assertThat(sent.stream().map(value -> value.content().targetName()))
                .contains("서울특별시 중구", "서울특별시 마포구", "서울특별시");
    }

    @Test
    void 첫_알림의_1분_경계를_지난_공고는_새_묶음으로_보낸다() {
        announcement(NOW);
        announcement(NOW.plusSeconds(61));
        var sizes = new ArrayList<Integer>();
        NotificationPushSender sender = value -> { sizes.add(value.members().size()); return accepted(); };
        assertThat(service(NOW.plusSeconds(61), sender).deliverAvailable(20)).isOne();
        assertThat(service(NOW.plusSeconds(121), sender).deliverAvailable(20)).isOne();
        assertThat(sizes).containsExactly(1, 1);
    }

    @Test
    void 묶음_lease를_함께_회수하고_이전_작업의_결과는_반영하지_않는다() {
        announcement(NOW);
        announcement(NOW.plusSeconds(20));
        var transaction = new TransactionTemplate(manager);
        var first = transaction.execute(status -> deliveries.claimNext(NOW.plusSeconds(60), java.time.Duration.ofSeconds(120))).orElseThrow();
        var recovered = transaction.execute(status -> deliveries.claimNext(NOW.plusSeconds(180), java.time.Duration.ofSeconds(120))).orElseThrow();
        assertThat(first.members()).hasSize(2);
        assertThat(recovered.members()).hasSize(2);
        assertThat(recovered.groupId()).isEqualTo(first.groupId());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        transaction.executeWithoutResult(status -> deliveries.recordResults(first.members(), accepted(), NOW.plusSeconds(181)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE device_id=? AND status='SENT'", Long.class, device)).isZero();
        transaction.executeWithoutResult(status -> deliveries.recordResults(recovered.members(), accepted(), NOW.plusSeconds(181)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE device_id=? AND status='SENT'", Long.class, device)).isEqualTo(2);
    }

    private void announcement(Instant now) {
        announcement(now, "11140", "1114010100");
    }
    private void announcement(Instant now, String district, String dong) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var f = new NotificationFixtures(entities, jdbc);
            var a = f.announcement(); var c = f.complex(district, dong);
            announcements.add(a.getId()); complexes.add(c.getId());
            f.link(a, c);
            long event = f.event(a);
            jdbc.update("UPDATE notification_outbox_events SET occurred_at=?,next_attempt_at=? WHERE id=?", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), event);
        });
        materialization.materializeNextPage(now, 200);
    }
    private NotificationSendResult accepted() { return new NotificationSendResult(Status.ACCEPTED, "test/message", null, null); }
    private NotificationDeliveryService service(Instant now, NotificationPushSender sender) {
        return new NotificationDeliveryService(deliveries, audience, sender, manager, Clock.fixed(now, ZoneOffset.UTC));
    }
}
