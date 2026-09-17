package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class MyHomeAnnouncementCollectionExecutionLock {

    private static final long LOCK_KEY = 8_432_026_082_800_017L;

    private final ReentrantLock localLock = new ReentrantLock();

    private final PostgresAdvisoryLock databaseLock;

    public MyHomeAnnouncementCollectionExecutionLock(DataSource dataSource) {
        this.databaseLock = new PostgresAdvisoryLock(dataSource);
    }

    public <T> Optional<T> tryRun(Supplier<T> operation) {
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try {
            Optional<PostgresAdvisoryLock.Lease> lease = databaseLock.tryAcquire(
                    LOCK_KEY,
                    "마이홈 공고 수집 실행"
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
                    "마이홈 공고 수집 실행 잠금을 처리하지 못했습니다.",
                    exception
            );
        }
        finally {
            localLock.unlock();
        }
    }
}
