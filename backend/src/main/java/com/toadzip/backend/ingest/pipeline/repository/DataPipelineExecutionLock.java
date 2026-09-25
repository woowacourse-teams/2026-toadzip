package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
        return tryAcquire(UUID.randomUUID());
    }

    public Optional<Lease> tryAcquire(UUID ownerId) {
        Objects.requireNonNull(ownerId, "실행 소유자 ID는 필수입니다.");
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
            PostgresAdvisoryLock.Lease databaseLease = lease.orElseThrow();
            try {
                long generation = claim(databaseLease, ownerId);
                return Optional.of(new JdbcLease(databaseLease, ownerId, generation));
            }
            catch (SQLException | RuntimeException exception) {
                databaseLease.close();
                throw exception;
            }
        }
        catch (SQLException | RuntimeException exception) {
            locallyLocked.set(false);
            throw new IllegalStateException(
                    "데이터 수집·정제 실행 잠금을 처리하지 못했습니다.",
                    exception
            );
        }
    }

    public boolean isHeld() {
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

        UUID ownerId();

        long generation();

        void verifyHeld();

        @Override
        void close();
    }

    private long claim(PostgresAdvisoryLock.Lease lease, UUID ownerId) throws SQLException {
        return lease.withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ingest_execution_ownership (id, generation, owner_id)
                    VALUES (1, 0, NULL) ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setQueryTimeout(30);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE ingest_execution_ownership
                    SET generation = generation + 1, owner_id = ? WHERE id = 1
                    RETURNING generation
                    """)) {
                statement.setQueryTimeout(30);
                statement.setObject(1, ownerId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("데이터 수집·정제 소유권 행을 찾지 못했습니다.");
                    }
                    return result.getLong(1);
                }
            }
        });
    }

    private final class JdbcLease implements Lease {

        private final AtomicBoolean closed = new AtomicBoolean();

        private final PostgresAdvisoryLock.Lease databaseLease;

        private final UUID ownerId;

        private final long generation;

        private final AtomicBoolean lost = new AtomicBoolean();

        private JdbcLease(PostgresAdvisoryLock.Lease databaseLease, UUID ownerId, long generation) {
            this.databaseLease = databaseLease;
            this.ownerId = ownerId;
            this.generation = generation;
        }

        @Override
        public UUID ownerId() {
            return ownerId;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public void verifyHeld() {
            if (closed.get() || lost.get()) {
                throw new IngestOwnershipLostException();
            }
            try {
                databaseLease.verifyHeld();
            }
            catch (SQLException exception) {
                lost.set(true);
                throw new IngestOwnershipLostException(exception);
            }
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
