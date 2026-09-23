package com.toadzip.backend.global.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisoryLockTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private PostgresAdvisoryLock advisoryLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        advisoryLock = new PostgresAdvisoryLock(dataSource);
    }

    @Test
    void 잠금_해제_결과가_false면_물리_연결을_폐기한다() throws Exception {
        when(resultSet.getBoolean(1)).thenReturn(true, false);
        PostgresAdvisoryLock.Lease lease = advisoryLock.tryAcquire(123L, "테스트 실행")
                .orElseThrow();

        lease.close();

        InOrder releaseOrder = inOrder(connection);
        releaseOrder.verify(connection).abort(any());
        releaseOrder.verify(connection).close();
    }

    @Test
    void 정상적으로_잠금을_해제하면_연결을_풀에_한_번_반환한다() throws Exception {
        when(resultSet.getBoolean(1)).thenReturn(true);
        PostgresAdvisoryLock.Lease lease = advisoryLock.tryAcquire(123L, "테스트 실행")
                .orElseThrow();

        lease.close();
        lease.close();

        verify(connection, times(1)).close();
        verify(connection, never()).abort(any());
        verify(statement, times(2)).executeQuery();
    }
}
