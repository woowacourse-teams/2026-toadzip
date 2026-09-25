package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementEnrichmentExecutionLock {

    // 마이홈 공고 매핑과 LH 보강은 같은 제품 공고를 수정하므로 실행 잠금을 공유한다.
    private static final long LOCK_KEY = 8_432_026_082_800_018L;

    private final PostgresAdvisoryLock databaseLock;

    private final ReentrantLock localLock = new ReentrantLock();

    public LhAnnouncementEnrichmentExecutionLock(DataSource dataSource) {
        this.databaseLock = new PostgresAdvisoryLock(dataSource);
    }

    public <T> Optional<T> tryRun(Supplier<T> action) {
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try {
            Optional<PostgresAdvisoryLock.Lease> lease = databaseLock.tryAcquire(
                    LOCK_KEY,
                    "LH 공고 보강 실행"
            );
            if (lease.isEmpty()) {
                return Optional.empty();
            }
            try (PostgresAdvisoryLock.Lease ignored = lease.orElseThrow()) {
                return Optional.of(action.get());
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("LH 공고 보강 잠금을 확인할 수 없습니다.", exception);
        }
        finally {
            localLock.unlock();
        }
    }
}
