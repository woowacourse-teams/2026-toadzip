package com.toadzip.backend.ingest.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class DataPipelineExecutionLock {

    private static final long LOCK_KEY = 8_432_026_090_100_001L;

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final AtomicBoolean locallyLocked = new AtomicBoolean();

    private final DataSource dataSource;

    public DataPipelineExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Lease> tryAcquire() {
        if (!locallyLocked.compareAndSet(false, true)) {
            return Optional.empty();
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!executeLockQuery(connection, TRY_LOCK_SQL)) {
                connection.close();
                locallyLocked.set(false);
                return Optional.empty();
            }
            return Optional.of(new JdbcLease(connection));
        }
        catch (SQLException exception) {
            closeConnectionAfterAcquireFailure(connection, exception);
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
        try (Connection connection = dataSource.getConnection()) {
            if (!executeLockQuery(connection, TRY_LOCK_SQL)) {
                return true;
            }
            if (!executeLockQuery(connection, UNLOCK_SQL)) {
                throw new IllegalStateException(
                        "데이터 수집·정제 실행 잠금 확인 후 해제하지 못했습니다."
                );
            }
            return false;
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

    private boolean executeLockQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void closeConnectionAfterAcquireFailure(Connection connection, SQLException cause) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        }
        catch (SQLException closeException) {
            cause.addSuppressed(closeException);
        }
    }

    private final class JdbcLease implements Lease {

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Connection connection;

        private JdbcLease(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                executeLockQuery(connection, UNLOCK_SQL);
            }
            catch (SQLException exception) {
                log.error(
                        "데이터 수집·정제 실행 잠금 해제에 실패했습니다. "
                                + "DB 연결 종료 시 잠금이 해제됩니다.",
                        exception
                );
            }
            finally {
                closeConnection();
                locallyLocked.set(false);
            }
        }

        private void closeConnection() {
            try {
                connection.close();
            }
            catch (SQLException exception) {
                log.error("데이터 수집·정제 잠금 연결을 닫지 못했습니다.", exception);
            }
        }
    }
}
