package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPipelineExecutionLockTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private DataPipelineExecutionLock executionLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        executionLock = new DataPipelineExecutionLock(dataSource);
    }

    @Test
    void 임대_중에는_중복_실행을_거부하고_반납하면_다시_획득한다() throws Exception {
        var lease = executionLock.tryAcquire().orElseThrow();

        assertThat(executionLock.tryAcquire()).isEmpty();
        lease.close();

        assertThat(executionLock.tryAcquire()).isPresent();
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void 요청_스레드에서_얻은_잠금을_작업_스레드에서_반납한다() throws Exception {
        var lease = executionLock.tryAcquire().orElseThrow();

        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(lease::close).get();
        }

        assertThat(executionLock.tryAcquire()).isPresent();
        verify(statement, times(3)).setLong(1, 8_432_026_090_100_001L);
    }
}
