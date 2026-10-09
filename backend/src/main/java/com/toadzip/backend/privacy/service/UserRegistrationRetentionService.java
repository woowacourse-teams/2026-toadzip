package com.toadzip.backend.privacy.service;

import com.toadzip.backend.privacy.repository.UserRegistrationNoticeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserRegistrationRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserRegistrationRetentionService.class);
    private static final int CHUNK_SIZE = 500;
    private final UserRegistrationNoticeRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final Counter deleted;

    public UserRegistrationRetentionService(UserRegistrationNoticeRepository repository,
            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(20);
        this.clock = clock;
        Tags tags = Tags.of("job", "registration-notice");
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
                Integer result = transactions.execute(status -> purgeChunk(now));
                count = Objects.requireNonNull(result);
                deleted.increment(count);
            } while (count == CHUNK_SIZE && System.nanoTime() < deadline);
            var remaining = repository.overdue(now);
            overdue.set(remaining.count());
            oldestSeconds.set((long) remaining.oldestSeconds());
            lastSuccess.set(clock.instant().getEpochSecond());
            failures.set(0);
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
            LOGGER.error("event=privacy.retention.failed job=registration-notice reason={}",
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private int purgeChunk(Instant now) {
        repository.markOrphans(now, CHUNK_SIZE);
        return repository.purgeOrphans(now, CHUNK_SIZE);
    }
}
