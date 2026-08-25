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

import com.toadzip.backend.ingest.domain.ExternalApi;

@Slf4j
@Repository
public class LhNoticeCollectionExecutionLock {

    private static final Map<ExternalApi, Long> LOCK_KEYS = Map.of(
            ExternalApi.LH_NOTICE_DETAIL, 8_432_026_082_400_001L,
            ExternalApi.LH_NOTICE_SUPPLY, 8_432_026_082_400_002L
    );

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final Map<ExternalApi, ReentrantLock> localLocks = new EnumMap<>(ExternalApi.class);

    private final DataSource dataSource;

    public LhNoticeCollectionExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
        LOCK_KEYS.keySet().forEach(externalApi -> localLocks.put(externalApi, new ReentrantLock()));
    }

    public <T> Optional<T> tryRun(ExternalApi externalApi, Supplier<T> operation) {
        ReentrantLock localLock = localLock(externalApi);
        if (!localLock.tryLock()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!executeLockQuery(connection, TRY_LOCK_SQL, lockKey(externalApi))) {
                return Optional.empty();
            }
            try {
                return Optional.of(operation.get());
            }
            finally {
                releaseDatabaseLock(connection, lockKey(externalApi));
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

    private ReentrantLock localLock(ExternalApi externalApi) {
        ReentrantLock localLock = localLocks.get(externalApi);
        if (localLock == null) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
        return localLock;
    }

    private long lockKey(ExternalApi externalApi) {
        Long lockKey = LOCK_KEYS.get(externalApi);
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
