package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.LocalDate;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.notification.repository.NotificationScheduleRepository;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
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
class NotificationScheduleIntegrationTest {
    @Autowired private NotificationScheduleService schedules;
    @Autowired private NotificationMaterializationService materialization;
    @Autowired private NotificationScheduleRepository scheduleRepository;
    @Autowired private UserNotificationRepository inbox;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    private Announcement announcement;
    private NotificationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new NotificationFixtures(entities, jdbc);
        announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        fixtures.user(63001);
        fixtures.subscribe(63001, "ANNOUNCEMENT", announcement.getId().toString(), NOW.minusSeconds(7200));
        fixtures.device(63001);
    }

    @Test
    void 접수_서류_당첨_계약의_전날_9시에_한번만_생성하고_상세일정을_보관한다() {
        for (var type : new ScheduleType[]{ScheduleType.APPLICATION, ScheduleType.DOCUMENT_SUBMISSION,
                ScheduleType.WINNER_ANNOUNCEMENT, ScheduleType.CONTRACT}) {
            schedule(type);
        }
        assertThat(schedules.publishDue(NOW.minusSeconds(3601))).isZero();
        assertThat(schedules.publishDue(NOW)).isEqualTo(4);
        assertThat(schedules.publishDue(NOW.plusSeconds(60))).isZero();
        for (int i = 0; i < 4; i++) assertThat(materialization.materializeNextPage(NOW, 200)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE user_id=63001", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT content FROM user_notifications WHERE user_id=63001", String.class))
                .allSatisfy(value -> assertThat(value).contains("SCHEDULE_REMINDER", "2026-10-14", "https://example.com/notice"));
    }

    @Test
    void 같은날_시작마감은_하나로_합치고_과거_알림은_새_구독자에게_소급하지_않는다() {
        schedule(ScheduleType.APPLICATION);
        fixtures.user(63002);
        fixtures.subscribe(63002, "ANNOUNCEMENT", announcement.getId().toString(), NOW);
        assertThat(schedules.publishDue(NOW)).isOne();
        assertThat(materialization.materializeNextPage(NOW, 200)).isOne();
        assertThat(jdbc.queryForList("SELECT user_id FROM user_notifications", Long.class)).containsExactly(63001L);
    }

    @Test
    void 발송준비_전에_변경되거나_삭제된_일정은_알리지_않는다() {
        schedule(ScheduleType.CONTRACT);
        assertThat(schedules.publishDue(NOW)).isOne();
        jdbc.update("DELETE FROM announcement_schedules WHERE announcement_id=?", announcement.getId());
        assertThat(materialization.materializeNextPage(NOW, 200)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox_events", String.class)).isEqualTo("SKIPPED");
    }

    @Test
    void 같은_일정을_새_ID로_다시_저장해도_중복_생성하지_않는다() {
        schedule(ScheduleType.DOCUMENT_SUBMISSION);
        assertThat(schedules.publishDue(NOW)).isOne();
        jdbc.update("DELETE FROM announcement_schedules WHERE announcement_id=?", announcement.getId());
        schedule(ScheduleType.DOCUMENT_SUBMISSION);
        assertThat(schedules.publishDue(NOW)).isZero();
    }

    @Test
    void 검수된_접수는_확정된_일정만_알리고_기존_요약일정과_조건부일정을_제외한다() {
        rawSchedule(ScheduleType.APPLICATION);
        jdbc.update("UPDATE announcements SET application_schedule_reviewed=true WHERE id=?", announcement.getId());
        for (var state : ApplicationScheduleState.values()) {
            entities.persist(AnnouncementApplicationSchedule.verified(announcement, null, "1순위", state,
                    state == ApplicationScheduleState.CONDITIONAL ? "선순위 미달 시" : null,
                    LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), null, null,
                    "https://example.com/verified.pdf", 3));
        }
        entities.flush();
        assertThat(schedules.publishDue(NOW)).isOne();
        assertThat(materialization.materializeNextPage(NOW, 200)).isOne();
        var item = inbox.findPage(63001, null, 20, NOW).items().getFirst();
        assertThat(item.type()).isEqualTo("SCHEDULE_REMINDER");
        assertThat(item.content().schedule().sourceUrl()).isEqualTo("https://example.com/verified.pdf");
        assertThat(item.content().schedule().sourcePage()).isEqualTo(3);
        assertThat(item.content().schedule().startTime()).isNull();
        assertThat(item.content().schedule().condition()).isNull();
    }

    @Test
    void 서로_다른날의_시작과_마감은_각_전날에_알리고_발표는_시작만_알린다() {
        schedule(ScheduleType.CONTRACT);
        schedule(ScheduleType.WINNER_ANNOUNCEMENT);
        jdbc.update("UPDATE announcement_schedules SET end_at='2026-10-16 17:00:00' WHERE announcement_id=?", announcement.getId());
        assertThat(scheduleRepository.due(NOW)).extracting(value -> value.phase()).containsOnly("START");
        assertThat(scheduleRepository.due(NOW.plusSeconds(2 * 86400)))
                .singleElement().satisfies(value -> {
                    assertThat(value.kind()).isEqualTo("CONTRACT");
                    assertThat(value.phase()).isEqualTo("END");
                });
    }

    @Test
    void 발송_직전_일정변경과_한국시간_자정_경과를_검증한다() {
        schedule(ScheduleType.CONTRACT);
        var reminder = scheduleRepository.due(NOW).getFirst();
        assertThat(scheduleRepository.isCurrent(reminder, NOW)).isTrue();
        assertThat(scheduleRepository.isCurrent(reminder, java.time.Instant.parse("2026-10-13T15:00:00Z"))).isFalse();
        jdbc.update("UPDATE announcement_schedules SET end_at='2026-10-14 18:00:00' WHERE announcement_id=?", announcement.getId());
        assertThat(scheduleRepository.isCurrent(reminder, NOW)).isFalse();
    }

    @Test
    void 공고_알림을_해제한_회원에게는_일정_알림을_만들지_않는다() {
        schedule(ScheduleType.CONTRACT);
        assertThat(schedules.publishDue(NOW)).isOne();
        jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=63001");
        assertThat(materialization.materializeNextPage(NOW, 200)).isZero();
        assertThat(inbox.countUnread(63001, NOW)).isZero();
    }

    @Test
    void 검수되지_않은_기존_접수_일정은_발송하지_않는다() {
        rawSchedule(ScheduleType.APPLICATION);
        assertThat(schedules.publishDue(NOW)).isZero();
    }

    @Test
    void 원천_시각이_없는_저장용_자정은_확정시각으로_표시하지_않는다() {
        rawSchedule(ScheduleType.CONTRACT);
        jdbc.update("UPDATE announcement_schedules SET start_at='2026-10-14 00:00:00',end_at='2026-10-14 00:00:00' WHERE announcement_id=?", announcement.getId());
        assertThat(scheduleRepository.due(NOW)).singleElement().satisfies(value -> {
            assertThat(value.startTime()).isNull();
            assertThat(value.endTime()).isNull();
        });
    }

    @Test
    void 수신자_없는_일정은_대기열을_채우지_않는다() {
        schedule(ScheduleType.CONTRACT);
        jdbc.update("UPDATE notification_subscriptions SET active=false WHERE user_id=63001");
        assertThat(schedules.publishDue(NOW)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
    }

    private void schedule(ScheduleType type) {
        if (type != ScheduleType.APPLICATION) {
            rawSchedule(type);
            return;
        }
        jdbc.update("UPDATE announcements SET application_schedule_reviewed=true WHERE id=?", announcement.getId());
        entities.persist(AnnouncementApplicationSchedule.verified(announcement, null, "1순위",
                ApplicationScheduleState.CONFIRMED, null, LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 14), java.time.LocalTime.of(10, 0), java.time.LocalTime.of(17, 0),
                "https://example.com/notice", 1));
        entities.flush();
    }

    private void rawSchedule(ScheduleType type) {
        entities.persist(AnnouncementSchedule.create(announcement, type, type.legacyStoredValue(),
                LocalDateTime.of(2026, 10, 14, 10, 0), LocalDateTime.of(2026, 10, 14, 17, 0), 0));
        entities.flush();
    }
}
