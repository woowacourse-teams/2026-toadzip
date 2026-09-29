package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestOperationLockTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;

    @ParameterizedTest
    @CsvSource({
            "LH_ANNOUNCEMENT_COLLECTION, 8432026082400001",
            "MYHOME_ANNOUNCEMENT_COLLECTION, 8432026082800017",
            "MYHOME_COMPLEX_MAPPING, 8432026082400003",
            "MYHOME_ANNOUNCEMENT_MAPPING, 8432026082800018",
            "LH_ANNOUNCEMENT_ENRICHMENT, 8432026082800018"
    })
    void 기존_인스턴스와_충돌하도록_DB_잠금_키를_유지한다(Operation operation, long expectedKey) throws Exception {
        allowDatabaseLock();
        var lock = new IngestOperationLock(dataSource);

        assertThat(lock.tryRun(operation, () -> "completed")).contains("completed");

        verify(statement, times(2)).setLong(1, expectedKey);
    }

    @ParameterizedTest
    @CsvSource({
            "LH_ANNOUNCEMENT_COLLECTION, LH 공고 수집 실행 잠금을 처리하지 못했습니다.",
            "MYHOME_ANNOUNCEMENT_COLLECTION, 마이홈 공고 수집 실행 잠금을 처리하지 못했습니다.",
            "MYHOME_COMPLEX_MAPPING, 마이홈 단지 매핑 실행 잠금을 처리하지 못했습니다.",
            "MYHOME_ANNOUNCEMENT_MAPPING, 마이홈 공고 매핑 실행 잠금을 처리하지 못했습니다.",
            "LH_ANNOUNCEMENT_ENRICHMENT, LH 공고 보강 잠금을 확인할 수 없습니다."
    })
    void DB_획득_실패의_메시지를_보존하고_다른_스레드의_재실행을_허용한다(
            Operation operation, String expectedMessage
    ) throws Exception {
        SQLException failure = new SQLException("connection unavailable");
        when(dataSource.getConnection()).thenThrow(failure);
        var lock = new IngestOperationLock(dataSource);

        assertThatThrownBy(() -> lock.tryRun(operation, () -> "unexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage)
                .hasCause(failure);

        doReturn(connection).when(dataSource).getConnection();
        allowDatabaseQueries();
        assertCanRunFromAnotherThread(lock, operation);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void 작업_예외를_보존하고_다른_스레드의_재실행을_허용한다(Operation operation) throws Exception {
        allowDatabaseLock();
        var lock = new IngestOperationLock(dataSource);
        RuntimeException failure = new IllegalArgumentException("operation failed");

        assertThatThrownBy(() -> lock.tryRun(operation, () -> {
            throw failure;
        })).isSameAs(failure);

        assertCanRunFromAnotherThread(lock, operation);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void DB_획득_거절_후에도_다른_스레드에서_재실행할_수_있다(Operation operation) throws Exception {
        allowDatabaseLock();
        when(resultSet.getBoolean(1)).thenReturn(false, true);
        var lock = new IngestOperationLock(dataSource);

        assertThat(lock.tryRun(operation, () -> {
            throw new AssertionError("거절된 작업을 실행했습니다.");
        })).isEmpty();

        assertCanRunFromAnotherThread(lock, operation);
    }

    private void allowDatabaseLock() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        allowDatabaseQueries();
    }

    private void allowDatabaseQueries() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
    }

    private void assertCanRunFromAnotherThread(IngestOperationLock lock, Operation operation) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(() -> lock.tryRun(operation, () -> "retried"))
                    .get(5, TimeUnit.SECONDS)).contains("retried");
        }
    }
}
