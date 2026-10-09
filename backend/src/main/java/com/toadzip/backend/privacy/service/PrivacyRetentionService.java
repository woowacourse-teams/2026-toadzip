package com.toadzip.backend.privacy.service;

import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.privacy.repository.PrivacyRetentionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PrivacyRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrivacyRetentionService.class);
    private static final int CHUNK_SIZE = 500;
    private final PrivacyRetentionRepository repository;
    private final PrivacyNoticeCatalog notices;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong overdueCount = new AtomicLong();
    private final AtomicLong oldestOverdueSeconds = new AtomicLong();
    private final Counter deleted;

    public PrivacyRetentionService(PrivacyRetentionRepository repository, PrivacyNoticeCatalog notices, Clock clock,
            PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.repository = repository;
        this.notices = notices;
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(20);
        deleted = Counter.builder("privacy.retention.deleted.total").tag("job", "consent").register(meters);
        register(meters, "privacy.retention.last.success.seconds", lastSuccess);
        register(meters, "privacy.retention.consecutive.failures", failures);
        register(meters, "privacy.retention.overdue.count", overdueCount);
        register(meters, "privacy.retention.oldest.overdue.seconds", oldestOverdueSeconds);
    }

    private void register(MeterRegistry meters, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue).tag("job", "consent").register(meters);
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Seoul")
    public void purgeExpiredData() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        try {
            int changed;
            do {
                Chunk result = transaction.execute(status -> purgeChunk(clock.instant()));
                deleted.increment(result.deleted());
                changed = result.changed();
            } while (changed > 0 && System.nanoTime() < deadline);
            Instant now = clock.instant();
            var overdue = repository.overdue(now, notices.requiredAnalyticsScope(), scopeDeadlines(),
                    fallbackScopeDeadline());
            overdueCount.set(overdue.count());
            oldestOverdueSeconds.set((long) overdue.oldestSeconds());
            failures.set(0);
            lastSuccess.set(now.getEpochSecond());
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
            LOGGER.error("event=privacy.retention.failed job=consent failures={} errorType={}",
                    failures.get(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private Chunk purgeChunk(Instant now) {
        String scope = notices.requiredAnalyticsScope();
        Instant scopePurgeAfter = fallbackScopeDeadline();
        Map<String, Instant> deadlines = scopeDeadlines();
        int changed = 0;
        int removed = 0;
        for (var candidate : repository.candidates(now, scope, deadlines, scopePurgeAfter, CHUNK_SIZE)) {
            if (changed >= CHUNK_SIZE) {
                break;
            }
            AnalyticsConsent consent = repository.lockCandidate(candidate);
            if (consent == null) {
                continue;
            }
            changed += repository.advanceScopePurge(consent.getId(), scope, deadlines,
                    scopePurgeAfter, CHUNK_SIZE - changed);
            int count = repository.deleteExpiredEvents(consent.getId(), now, CHUNK_SIZE - changed);
            changed += count;
            removed += count;
            if (changed < CHUNK_SIZE && repository.guestCanBePurged(consent, now)) {
                count = repository.deleteGuest(consent);
                changed += count;
                removed += count;
            }
        }
        return new Chunk(changed, removed);
    }

    private Instant fallbackScopeDeadline() {
        return notices.analyticsScopeEffectiveAt().plus(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION);
    }

    private Map<String, Instant> scopeDeadlines() {
        return notices.scopeInvalidations().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().plus(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION)));
    }

    private record Chunk(int changed, int deleted) {
    }
}
