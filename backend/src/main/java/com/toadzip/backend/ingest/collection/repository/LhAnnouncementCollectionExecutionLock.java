package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementCollectionExecutionLock {

    private static final Map<ExternalDataSource, Long> LOCK_KEYS = Map.of(
            ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, 8_432_026_082_400_001L,
            ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, 8_432_026_082_400_001L,
            ExternalDataSource.LH_ANNOUNCEMENT_CATALOG, 8_432_026_082_400_001L
    );

    private final ReentrantLock localLock = new ReentrantLock();

    private final PostgresAdvisoryLock databaseLock;

    public LhAnnouncementCollectionExecutionLock(DataSource dataSource) {
        this.databaseLock = new PostgresAdvisoryLock(dataSource);
    }

    public <T> Optional<T> tryRun(ExternalDataSource source, Supplier<T> operation) {
        long key = lockKey(source);
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try {
            Optional<PostgresAdvisoryLock.Lease> lease = databaseLock.tryAcquire(
                    key,
                    "LH 공고 수집 실행"
            );
            if (lease.isEmpty()) {
                return Optional.empty();
            }
            try (PostgresAdvisoryLock.Lease ignored = lease.orElseThrow()) {
                return Optional.of(operation.get());
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException(
                    "LH 공고 수집 실행 잠금을 처리하지 못했습니다.",
                    exception
            );
        }
        finally {
            localLock.unlock();
        }
    }

    private long lockKey(ExternalDataSource source) {
        Long lockKey = LOCK_KEYS.get(source);
        if (lockKey == null) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
        return lockKey;
    }
}
