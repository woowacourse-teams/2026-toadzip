package com.toadzip.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class AnnouncementNotificationOutboxIntegrationTest {
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 꺼진_생성기는_후보_공고도_발행하지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var announcement = Announcement.create(
                    "disabled-probe", null, null, "새 공고",
                    AnnouncementPublicationType.ORIGINAL,
                    RentalType.NATIONAL_RENTAL,
                    RecruitmentType.NEW,
                    AgencyCode.LH,
                    LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 15),
                    LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 1),
                    "https://example.com/notice", null, 0L, null);
            ReflectionTestUtils.setField(announcement, "id", 101L);
            ReflectionTestUtils.setField(announcement, "createdAt", Instant.now());
            assertThat(outbox.appendNewAnnouncement(announcement)).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM notification_outbox_events WHERE source_identifier='disabled-probe'",
                    Long.class)).isZero();
        });
    }

    @Test
    void 호출자_트랜잭션이_없으면_기록하지_않는다() {
        assertThatThrownBy(() -> outbox.appendNewAnnouncement(101, "required-transaction", Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 상위_트랜잭션이_롤백되면_아웃박스도_남지_않는다() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outbox.appendNewAnnouncement(101, "rollback-probe", Instant.now());
            throw new IllegalStateException("rollback-probe");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events WHERE source_identifier = ?",
                Long.class, "rollback-probe")).isZero();
    }

    @Test
    void 같은_이벤트키를_반복기록해도_한건만_남는다() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            try {
                assertThat(outbox.appendNewAnnouncement(101, "deduplication-probe", Instant.now())).isTrue();
                assertThat(outbox.appendNewAnnouncement(101, "deduplication-probe", Instant.now())).isFalse();
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification_outbox_events WHERE source_identifier = ?",
                        Long.class, "deduplication-probe")).isOne();
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
