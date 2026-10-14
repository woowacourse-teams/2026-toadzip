package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
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
class NotificationMaterializationIntegrationTest {
    @Autowired private NotificationMaterializationService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    private NotificationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new NotificationFixtures(entities, jdbc);
    }

    @Test
    void 이미_만든_알림을_재처리해도_나중에_등록한_기기에_소급_발송하지_않는다() {
        var announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        long event = fixtures.event(announcement);
        fixtures.user(190);
        fixtures.subscribe(190, "REGION", "11", NOW.minusSeconds(60));
        service.materializeNextPage(NOW, 200);
        fixtures.device(190);
        jdbc.update("UPDATE notification_outbox_events SET status='PENDING',recipient_cursor=0 WHERE id=?", event);
        service.materializeNextPage(NOW.plusSeconds(60), 200);
        assertThat(count("user_notifications")).isOne();
        assertThat(count("notification_deliveries")).isZero();
    }

    @Test
    void 지역과_단지와_공급행이_겹쳐도_회원별로_한건만_생성한다() {
        var announcement = fixtures.announcement();
        var complex = fixtures.complex("11140", "1114010100");
        fixtures.link(announcement, complex);
        fixtures.link(announcement, complex);
        fixtures.event(announcement);
        for (long user = 101; user <= 102; user++) {
            fixtures.user(user);
            fixtures.subscribe(user, "COMPLEX", complex.getId().toString(), NOW.minusSeconds(60));
            fixtures.subscribe(user, "REGION", "11", NOW.minusSeconds(60));
        }
        fixtures.device(101);
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(2);
        assertThat(service.materializeNextPage(NOW, 200)).isZero();
        assertThat(count("user_notifications")).isEqualTo(2);
        assertThat(count("notification_deliveries")).isEqualTo(1);
    }

    @Test
    void 만료_해제_뒤늦은_구독은_제외하고_시군구와_읍면동을_매칭한다() {
        var announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        fixtures.event(announcement);
        for (long user = 111; user <= 116; user++) {
            fixtures.user(user);
        }
        fixtures.subscribe(111, "REGION", "11140", NOW.minusSeconds(60));
        fixtures.subscribe(112, "REGION", "1114010100", NOW.minusSeconds(60));
        fixtures.subscribe(113, "REGION", "11", NOW.plusSeconds(1));
        fixtures.subscribe(114, "REGION", "11", NOW.minusSeconds(60));
        fixtures.subscribe(115, "REGION", "11", NOW.minusSeconds(60));
        fixtures.subscribe(116, "REGION", "26", NOW.minusSeconds(60));
        jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=114");
        jdbc.update("UPDATE notification_subscriptions SET expires_at=? WHERE user_id=115", java.sql.Timestamp.from(NOW));
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT user_id FROM user_notifications ORDER BY user_id", Long.class))
                .containsExactly(111L, 112L);
    }

    @Test
    void 사백오십명을_페이지별로_처리하고_중간_취소를_반영한다() {
        var announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        fixtures.event(announcement);
        for (long user = 1000; user < 1450; user++) {
            fixtures.user(user);
            fixtures.subscribe(user, "REGION", "11", NOW.minusSeconds(60));
        }
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(200);
        jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=1400");
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(200);
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(49);
        assertThat(count("user_notifications")).isEqualTo(449);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void 단지_연결을_기다리되_24시간후에는_실패로_남긴다() {
        fixtures.event(fixtures.announcement());
        service.materializeNextPage(NOW, 200);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class))
                .isEqualTo("PENDING");
        service.materializeNextPage(NOW.plusSeconds(86400), 200);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void 삭제된_공고는_건너뛴다() {
        var announcement = fixtures.announcement();
        fixtures.event(announcement);
        announcement.moveToTrash();
        entities.flush();
        service.materializeNextPage(NOW, 200);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class))
                .isEqualTo("SKIPPED");
    }

    @Test
    void 행정구역_변경_별칭도_검색과_동일하게_매칭한다() {
        var announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("29110", "2911010100"));
        fixtures.event(announcement);
        fixtures.user(121);
        fixtures.subscribe(121, "REGION", "12210", NOW.minusSeconds(60));
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(1);
    }

    @Test
    void 후속_공고가_있으면_원공고_알림을_생성하지_않는다() {
        var original = fixtures.announcement();
        var successor = fixtures.announcement();
        fixtures.event(original);
        jdbc.update("UPDATE announcements SET previous_announcement_id=? WHERE id=?",
                original.getId(), successor.getId());
        service.materializeNextPage(NOW, 200);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class))
                .isEqualTo("SKIPPED");
        assertThat(count("user_notifications")).isZero();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
