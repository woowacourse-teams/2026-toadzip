package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class DataPipelineExecutionLock {

    private static final long LOCK_KEY = 8_432_026_090_100_001L;

    private final AtomicBoolean locallyLocked = new AtomicBoolean();

    private final PostgresAdvisoryLock databaseLock;

    public DataPipelineExecutionLock(DataSource dataSource) {
        this.databaseLock = new PostgresAdvisoryLock(dataSource);
    }

    public Optional<Lease> tryAcquire() {
        if (!locallyLocked.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            Optional<PostgresAdvisoryLock.Lease> lease = databaseLock.tryAcquire(
                    LOCK_KEY,
                    "데이터 수집·정제 실행"
            );
            if (lease.isEmpty()) {
                locallyLocked.set(false);
                return Optional.empty();
            }
            return Optional.of(new JdbcLease(lease.orElseThrow()));
        }
        catch (SQLException exception) {
            locallyLocked.set(false);
            throw new IllegalStateException(
                    "데이터 수집·정제 실행 잠금을 처리하지 못했습니다.",
                    exception
            );
        }
    }

    public boolean isHeld() {
        if (locallyLocked.get()) {
            return true;
        }
        try {
            return databaseLock.isHeld(LOCK_KEY, "데이터 수집·정제 실행");
        }
        catch (SQLException exception) {
            throw new IllegalStateException(
                    "데이터 수집·정제 실행 잠금 상태를 확인하지 못했습니다.",
                    exception
            );
        }
    }

    public interface Lease extends AutoCloseable {

        @Override
        void close();
    }

    private final class JdbcLease implements Lease {

        private final AtomicBoolean closed = new AtomicBoolean();

        private final PostgresAdvisoryLock.Lease databaseLease;

        private JdbcLease(PostgresAdvisoryLock.Lease databaseLease) {
            this.databaseLease = databaseLease;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                databaseLease.close();
            }
            finally {
                locallyLocked.set(false);
            }
        }
    }
}
