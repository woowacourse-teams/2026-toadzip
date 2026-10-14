package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationMaintenanceIntegrationTest {
    @Autowired private NotificationMaintenanceService maintenance;
    @Autowired private com.toadzip.backend.notification.repository.NotificationMaintenanceRepository repository;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    private NotificationFixtures fixtures;
    private long event;
    private long announcement;
    private long member = 63000;

    @BeforeEach
    void seed() {
        fixtures = new NotificationFixtures(entities, jdbc);
        var source = fixtures.announcement();
        announcement = source.getId();
        event = fixtures.event(source);
        jdbc.update("UPDATE notification_outbox_events SET occurred_at=?,status='COMPLETED',target_snapshot='{}' WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofDays(100))), event);
    }

    @Test
    void 만료된_알림을_지우고_이벤트키는_유지한다() {
        long notification = notification(91);
        maintenance.purge(NOW);
        assertThat(exists(notification)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events WHERE id=?", Long.class, event))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT target_snapshot FROM notification_outbox_events WHERE id=?",
                String.class, event)).isNull();
    }

    @Test
    void 완료는_30일_실패는_90일_이후에만_삭제한다() {
        long oldSent = delivery("SENT", 31);
        long recentSent = delivery("SENT", 29);
        long oldFailed = delivery("FAILED", 91);
        long recentFailed = delivery("FAILED", 89);
        maintenance.purge(NOW);
        assertThat(deliveryExists(oldSent)).isFalse();
        assertThat(deliveryExists(recentSent)).isTrue();
        assertThat(deliveryExists(oldFailed)).isFalse();
        assertThat(deliveryExists(recentFailed)).isTrue();
    }

    @Test
    void 대기_발송이_참조하는_만료된_알림과_이벤트정보는_보존한다() {
        long delivery = delivery("PENDING", 100);
        maintenance.purge(NOW);
        assertThat(deliveryExists(delivery)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE event_id=?", Long.class, event))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT target_snapshot FROM notification_outbox_events WHERE id=?",
                String.class, event)).isEqualTo("{}");
    }

    @Test
    void 한_회차에서_500개까지만_삭제한다() {
        for (int index = 0; index < 501; index++) {
            notification(91);
        }
        maintenance.purge(NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE event_id=?", Long.class, event))
                .isOne();
    }

    @Test
    void 대기열_지표는_진행_중인_건수와_실패를_분리한다() {
        delivery("PENDING", 0);
        delivery("FAILED", 0);
        var stats = repository.statistics(NOW).stream().filter(value -> value.queue().equals("delivery")).findFirst().orElseThrow();
        assertThat(stats.pending()).isOne();
        assertThat(stats.failed()).isOne();
        assertThat(stats.delay()).isZero();
    }

    private long notification(int age) {
        fixtures.user(++member);
        return jdbc.queryForObject("""
                INSERT INTO user_notifications(user_id,event_id,announcement_id,title,created_at,expires_at)
                VALUES(?,?,?,'보관 테스트',?,?) RETURNING id
                """, Long.class, member, event, announcement, Timestamp.from(NOW.minus(Duration.ofDays(age))),
                Timestamp.from(NOW.minus(Duration.ofDays(age)).plus(Duration.ofDays(90))));
    }

    private long delivery(String status, int finishedAge) {
        long notification = notification(100);
        UUID device = fixtures.device(member);
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries(notification_id,device_id,device_binding_version,status,attempts,
                    next_attempt_at,finished_at) VALUES(?,?,1,?,1,?,?) RETURNING id
                """, Long.class, notification, device, status, Timestamp.from(NOW),
                Timestamp.from(NOW.minus(Duration.ofDays(finishedAge))));
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_notifications WHERE id=?)", Boolean.class, id);
    }

    private boolean deliveryExists(long id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_deliveries WHERE id=?)", Boolean.class, id);
    }
}
