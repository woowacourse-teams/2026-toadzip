package com.toadzip.backend.privacy.service;

import com.toadzip.backend.privacy.repository.PrivacyNotificationRetentionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PrivacyNotificationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyNotificationRetentionService.class);
    private static final int CHUNK_SIZE = 500;
    private static final long MAX_RUN_NANOS = 30_000_000_000L;
    private final PrivacyNotificationRetentionRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private final AtomicLong overdueCount = new AtomicLong();
    private final AtomicLong oldestOverdueSeconds = new AtomicLong();
    private final Counter deletedCount;

    public PrivacyNotificationRetentionService(PrivacyNotificationRetentionRepository repository, Clock clock,
            PlatformTransactionManager transactionManager, MeterRegistry registry) {
        this.repository = repository;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(20);
        registerGauge(registry, "privacy.retention.last.success.seconds", lastSuccess);
        registerGauge(registry, "privacy.retention.consecutive.failures", consecutiveFailures);
        registerGauge(registry, "privacy.retention.overdue.count", overdueCount);
        registerGauge(registry, "privacy.retention.oldest.overdue.seconds", oldestOverdueSeconds);
        this.deletedCount = Counter.builder("privacy.retention.deleted.total").tag("job", "notification")
                .register(registry);
    }

    @Scheduled(cron = "${app.privacy.retention-cron:0 */15 * * * *}", zone = "Asia/Seoul")
    public void purgeExpiredData() {
        Instant now = clock.instant();
        long started = System.nanoTime();
        try {
            int deleted;
            do {
                Integer result = transactions.execute(status -> repository.purgeChunk(now, CHUNK_SIZE));
                deleted = 0;
                if (result != null) {
                    deleted = result;
                }
                deletedCount.increment(deleted);
            } while (deleted > 0 && System.nanoTime() - started < MAX_RUN_NANOS);
            PrivacyNotificationRetentionRepository.Backlog backlog = repository.backlog(now);
            overdueCount.set(backlog.count());
            oldestOverdueSeconds.set(backlog.oldestOverdueSeconds());
            lastSuccess.set(clock.instant().getEpochSecond());
            consecutiveFailures.set(0);
        } catch (RuntimeException exception) {
            consecutiveFailures.incrementAndGet();
            // SQL and exception messages may contain identifiers. Expose only the failure category.
            log.error("Notification retention failed; category={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void registerGauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue).tag("job", "notification").register(registry);
    }
}
