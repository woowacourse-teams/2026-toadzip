package com.toadzip.backend.notification.repository;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.service.NotificationReadService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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
class NotificationReadConcurrencyIntegrationTest {
    private static final long MEMBER = 61001;
    @Autowired private UserNotificationRepository notifications;
    @Autowired private NotificationReadService reads;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    private final List<Long> events = new ArrayList<>();
    private final List<Long> announcements = new ArrayList<>();
    private long first;

    @BeforeEach
    void seed() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var fixtures = new NotificationFixtures(entities, jdbc);
            fixtures.user(MEMBER);
            for (int index = 0; index < 3; index++) {
                var announcement = fixtures.announcement();
                announcements.add(announcement.getId());
                events.add(fixtures.event(announcement));
            }
            first = insert(0);
        });
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM user_notifications WHERE user_id=?", MEMBER);
            for (long event : events) {
                jdbc.update("DELETE FROM notification_outbox_events WHERE id=?", event);
            }
            for (long announcement : announcements) {
                jdbc.update("DELETE FROM announcements WHERE id=?", announcement);
            }
            jdbc.update("DELETE FROM users WHERE id=?", MEMBER);
        });
    }

    @Test
    void 생성_커밋순서와_ID가_역전되지_않고_모두읽음은_조회한_경계만_읽는다() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var secondPid = new CompletableFuture<Integer>();
        var readPid = new CompletableFuture<Integer>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delayed = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                long id = insert(1);
                inserted.countDown();
                await(release);
                return id;
            }));
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                var observed = notifications.findPage(MEMBER, null, 20, NOW);
                assertThat(observed.readThrough()).isEqualTo(Long.toString(first));
                var later = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                    secondPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return insert(2);
                }));
                awaitAdvisoryWait(secondPid.get(5, TimeUnit.SECONDS));
                var markRead = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                    readPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    reads.markAllRead(MEMBER, observed.readThrough());
                    return true;
                }));
                awaitAdvisoryWait(readPid.get(5, TimeUnit.SECONDS));
                release.countDown();
                assertThat(later.get(5, TimeUnit.SECONDS)).isGreaterThan(delayed.get(5, TimeUnit.SECONDS));
                assertThat(markRead.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
        assertThat(notifications.countUnread(MEMBER, NOW)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT read_at IS NOT NULL FROM user_notifications WHERE id=?",
                Boolean.class, first)).isTrue();
    }

    private long insert(int index) {
        return notifications.insertIfAbsent(MEMBER, events.get(index), announcements.get(index), "새 공고", NOW).id();
    }

    private void awaitAdvisoryWait(int pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted)
                    """, Boolean.class, pid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("사용자 advisory lock을 기다리지 않았습니다.");
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
