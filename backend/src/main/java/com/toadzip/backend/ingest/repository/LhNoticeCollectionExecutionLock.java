package com.toadzip.backend.ingest.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class LhNoticeCollectionExecutionLock {

    private static final long LOCK_KEY = 8_432_026_082_400_001L;

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final ReentrantLock localLock = new ReentrantLock();

    private final DataSource dataSource;

    public LhNoticeCollectionExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> Optional<T> tryRun(Supplier<T> operation) {
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!executeLockQuery(connection, TRY_LOCK_SQL)) {
                return Optional.empty();
            }
            try {
                return Optional.of(operation.get());
            }
            finally {
                releaseDatabaseLock(connection);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("LH 공고 수집 실행 잠금을 처리하지 못했습니다.", exception);
        }
        finally {
            localLock.unlock();
        }
    }

    private boolean executeLockQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void releaseDatabaseLock(Connection connection) {
        try {
            executeLockQuery(connection, UNLOCK_SQL);
        }
        catch (SQLException exception) {
            log.error("LH 공고 수집 실행 잠금 해제에 실패했습니다. DB 연결 종료 시 잠금이 해제됩니다.", exception);
        }
    }
}
