package com.toadzip.backend.global.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PostgresAdvisoryLock {

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private final DataSource dataSource;

    public PostgresAdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Lease> tryAcquire(long lockKey, String lockName) throws SQLException {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!executeLockQuery(connection, TRY_LOCK_SQL, lockKey)) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new Lease(connection, lockKey, lockName));
        }
        catch (SQLException exception) {
            abort(connection, exception);
            throw exception;
        }
    }

    public boolean isHeld(long lockKey, String lockName) throws SQLException {
        Connection connection = null;
        boolean aborted = false;
        try {
            connection = dataSource.getConnection();
            if (!executeLockQuery(connection, TRY_LOCK_SQL, lockKey)) {
                return true;
            }
            if (!executeLockQuery(connection, UNLOCK_SQL, lockKey)) {
                throw new SQLException(lockName + " 확인용 잠금을 해제하지 못했습니다.");
            }
            return false;
        }
        catch (SQLException exception) {
            aborted = connection != null;
            abort(connection, exception);
            throw exception;
        }
        finally {
            if (!aborted) {
                close(connection, lockName);
            }
        }
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

    private void abort(Connection connection, SQLException cause) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(DIRECT_EXECUTOR);
        }
        catch (SQLException abortException) {
            cause.addSuppressed(abortException);
        }
        try {
            connection.close();
        }
        catch (SQLException closeException) {
            cause.addSuppressed(closeException);
        }
    }

    private void close(Connection connection, String lockName) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        }
        catch (SQLException exception) {
            log.error("{} DB 연결을 닫지 못했습니다.", lockName, exception);
        }
    }

    public final class Lease implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Connection connection;

        private final long lockKey;

        private final String lockName;

        private Lease(Connection connection, long lockKey, String lockName) {
            this.connection = connection;
            this.lockKey = lockKey;
            this.lockName = lockName;
        }

        public synchronized <T> T withConnection(ConnectionOperation<T> operation) throws SQLException {
            if (closed.get()) {
                throw new SQLException(lockName + " 잠금이 이미 반납되었습니다.");
            }
            return operation.execute(connection);
        }

        public synchronized void verifyHeld() throws SQLException {
            withConnection(heldConnection -> {
                try (PreparedStatement statement = heldConnection.prepareStatement("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_locks
                            WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted
                              AND classid = (? >> 32)::oid
                              AND objid = (? & 4294967295)::oid AND objsubid = 1
                        )
                        """)) {
                    statement.setQueryTimeout(5);
                    statement.setLong(1, lockKey);
                    statement.setLong(2, lockKey);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) {
                            throw new SQLException(lockName + " 잠금 소유권이 없습니다.");
                        }
                    }
                }
                return null;
            });
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean connectionReleased = false;
            try {
                if (!executeLockQuery(connection, UNLOCK_SQL, lockKey)) {
                    throw new SQLException(lockName + " 잠금을 해제하지 못했습니다.");
                }
            }
            catch (SQLException exception) {
                connectionReleased = true;
                abort(connection, exception);
                log.error("{} 잠금 해제에 실패해 DB 연결을 폐기합니다.", lockName, exception);
            }
            finally {
                if (!connectionReleased) {
                    PostgresAdvisoryLock.this.close(connection, lockName);
                }
            }
        }
    }

    @FunctionalInterface
    public interface ConnectionOperation<T> {

        T execute(Connection connection) throws SQLException;
    }
}
