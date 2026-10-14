package com.toadzip.backend.notification.service;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class NotificationMaterializationConcurrencyIntegrationTest {
    @Autowired private NotificationMaterializationService service;
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    private long eventId;
    private long announcementId;
    private long complexId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var fixtures = new NotificationFixtures(entities, jdbc);
            var announcement = fixtures.announcement();
            var complex = fixtures.complex("11140", "1114010100");
            fixtures.link(announcement, complex);
            announcementId = announcement.getId();
            complexId = complex.getId();
            eventId = fixtures.event(announcement);
            for (long user = 5000; user < 5450; user++) {
                fixtures.user(user);
                fixtures.subscribe(user, "REGION", "11", NOW.minusSeconds(60));
            }
        });
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM user_notifications WHERE event_id=?", eventId);
            jdbc.update("DELETE FROM notification_outbox_events WHERE id=?", eventId);
            jdbc.update("DELETE FROM supply_rows WHERE announcement_id=?", announcementId);
            jdbc.update("DELETE FROM announcements WHERE id=?", announcementId);
            jdbc.update("DELETE FROM housing_complexes WHERE id=?", complexId);
            jdbc.update("DELETE FROM notification_subscriptions WHERE user_id>=5000 AND user_id<5450");
            jdbc.update("DELETE FROM users WHERE id>=5000 AND id<5450");
        });
    }

    @Test
    void 두_연결은_같은_이벤트를_동시에_처리하지_않는다() throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertThat(outbox.claimNext(NOW).orElseThrow().id()).isEqualTo(eventId);
                claimed.countDown();
                await(release);
                return service.materializeNextPage(NOW, 200);
            }));
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> service.materializeNextPage(NOW, 200));
                assertThat(second.get(5, TimeUnit.SECONDS)).isZero();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(count()).isEqualTo(200);
    }

    @Test
    void 두번째_페이지_롤백후_재처리해도_누락과_중복이_없다() {
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(200);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(200);
            status.setRollbackOnly();
        });
        assertThat(count()).isEqualTo(200);
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(200);
        assertThat(service.materializeNextPage(NOW, 200)).isEqualTo(50);
        assertThat(count()).isEqualTo(450);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE event_id=?", Long.class, eventId);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
