package com.toadzip.backend.ingest.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.ingest.domain.ExternalDataSource;

@Slf4j
@Repository
public class LhNoticeCollectionExecutionLock {

    private static final Map<ExternalDataSource, Long> LOCK_KEYS = Map.of(
            ExternalDataSource.LH_NOTICE_DETAIL, 8_432_026_082_400_001L,
            ExternalDataSource.LH_NOTICE_SUPPLY, 8_432_026_082_400_002L
    );

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final Map<ExternalDataSource, ReentrantLock> localLocks = new EnumMap<>(ExternalDataSource.class);

    private final DataSource dataSource;

    public LhNoticeCollectionExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
        LOCK_KEYS.keySet().forEach(source -> localLocks.put(source, new ReentrantLock()));
    }

    public <T> Optional<T> tryRun(ExternalDataSource source, Supplier<T> operation) {
        ReentrantLock localLock = localLock(source);
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!executeLockQuery(connection, TRY_LOCK_SQL, lockKey(source))) {
                return Optional.empty();
            }
            try {
                return Optional.of(operation.get());
            }
            finally {
                releaseDatabaseLock(connection, lockKey(source));
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

    private ReentrantLock localLock(ExternalDataSource source) {
        ReentrantLock localLock = localLocks.get(source);
        if (localLock == null) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
        return localLock;
    }

    private long lockKey(ExternalDataSource source) {
        Long lockKey = LOCK_KEYS.get(source);
        if (lockKey == null) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
        return lockKey;
    }

    private boolean executeLockQuery(
            Connection connection,
            String sql,
            long lockKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void releaseDatabaseLock(Connection connection, long lockKey) {
        try {
            executeLockQuery(connection, UNLOCK_SQL, lockKey);
        }
        catch (SQLException exception) {
            log.error(
                    "LH 공고 수집 실행 잠금 해제에 실패했습니다. "
                            + "DB 연결 종료 시 잠금이 해제됩니다.",
                    exception
            );
        }
    }
}
