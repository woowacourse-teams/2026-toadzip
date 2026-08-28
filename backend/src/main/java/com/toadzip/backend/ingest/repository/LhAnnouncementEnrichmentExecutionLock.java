package com.toadzip.backend.ingest.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementEnrichmentExecutionLock {

    private static final long LOCK_KEY = 8_432_026_082_800_019L;
    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final DataSource dataSource;
    private final ReentrantLock localLock = new ReentrantLock();

    public LhAnnouncementEnrichmentExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> Optional<T> tryRun(Supplier<T> action) {
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                return Optional.empty();
            }
            try {
                return Optional.of(action.get());
            }
            finally {
                unlock(connection);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("LH 공고 보강 잠금을 확인할 수 없습니다.", exception);
        }
        finally {
            localLock.unlock();
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setLong(1, LOCK_KEY);
            statement.executeQuery();
        }
    }
}
