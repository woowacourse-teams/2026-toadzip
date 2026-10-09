package com.toadzip.backend.user.service;

import com.toadzip.backend.user.repository.UserLifecycleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class UserDeletionRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDeletionRetentionService.class);
    private final UserLifecycleRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final Counter deleted;

    public UserDeletionRetentionService(UserLifecycleRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(20);
        this.clock = clock;
        var tags = io.micrometer.core.instrument.Tags.of("job", "user-deletion");
        meters.gauge("privacy.retention.last.success.seconds", tags, lastSuccess);
        meters.gauge("privacy.retention.consecutive.failures", tags, failures);
        meters.gauge("privacy.retention.overdue.count", tags, overdue);
        meters.gauge("privacy.retention.oldest.overdue.seconds", tags, oldestSeconds);
        deleted = meters.counter("privacy.retention.deleted.total", tags);
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Seoul")
    public void purge() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        try {
            int count;
            do {
                Integer result = transactions.execute(status -> repository.purgeExpiredMarkers(now));
                count = java.util.Objects.requireNonNull(result);
                deleted.increment(count);
            } while (count == 500 && System.nanoTime() < deadline);
            var remaining = repository.overdueMarkers(now);
            overdue.set(remaining.count());
            oldestSeconds.set((long) remaining.oldestSeconds());
            lastSuccess.set(clock.instant().getEpochSecond());
            failures.set(0);
            if (oldestSeconds.get() > 1800) {
                LOGGER.warn("event=privacy.retention.overdue job=user-deletion");
            }
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
            LOGGER.error("event=privacy.retention.failed job=user-deletion reason={}",
                    exception.getClass().getSimpleName());
        }
    }
}
