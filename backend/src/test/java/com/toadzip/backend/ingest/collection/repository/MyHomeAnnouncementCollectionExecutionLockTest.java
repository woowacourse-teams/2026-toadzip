package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyHomeAnnouncementCollectionExecutionLockTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private MyHomeAnnouncementCollectionExecutionLock executionLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        executionLock = new MyHomeAnnouncementCollectionExecutionLock(dataSource);
    }

    @Test
    void DB_실행_잠금을_획득하면_수집을_실행하고_잠금을_해제한다() throws Exception {
        var result = executionLock.tryRun(() -> "completed");

        assertThat(result).contains("completed");
        verify(statement, org.mockito.Mockito.times(2)).setLong(1, 8_432_026_082_800_017L);
    }

    @Test
    void 다른_인스턴스가_DB_실행_잠금을_보유하면_수집을_실행하지_않는다() throws Exception {
        AtomicBoolean operationExecuted = new AtomicBoolean();
        when(resultSet.getBoolean(1)).thenReturn(false);

        var result = executionLock.tryRun(() -> {
            operationExecuted.set(true);
            return "duplicate";
        });

        assertThat(result).isEmpty();
        assertThat(operationExecuted).isFalse();
        verify(statement).setLong(1, 8_432_026_082_800_017L);
    }

    @Test
    void 잠금_해제_쿼리가_실패하면_물리_연결을_폐기한다() throws Exception {
        when(statement.executeQuery())
                .thenReturn(resultSet)
                .thenThrow(new SQLException("unlock query failed"));

        var result = executionLock.tryRun(() -> "completed");

        assertThat(result).contains("completed");
        InOrder releaseOrder = inOrder(connection);
        releaseOrder.verify(connection).abort(any());
        releaseOrder.verify(connection).close();
    }

    @Test
    void 작업과_잠금_해제가_모두_실패하면_작업_예외를_보존하고_연결을_폐기한다() throws Exception {
        when(statement.executeQuery())
                .thenReturn(resultSet)
                .thenThrow(new SQLException("unlock query failed"));

        assertThatThrownBy(() -> executionLock.tryRun(() -> {
            throw new IllegalStateException("operation failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("operation failed");

        InOrder releaseOrder = inOrder(connection);
        releaseOrder.verify(connection).abort(any());
        releaseOrder.verify(connection).close();
    }
}
