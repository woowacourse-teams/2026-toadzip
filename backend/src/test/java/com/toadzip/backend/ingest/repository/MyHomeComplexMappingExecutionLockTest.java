package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexMappingExecutionLockTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private MyHomeComplexMappingExecutionLock executionLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        executionLock = new MyHomeComplexMappingExecutionLock(dataSource);
    }

    @Test
    void 같은_인스턴스의_중복_매핑_실행을_거절한다() throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var runningOperation = executor.submit(() -> executionLock.tryRun(() -> {
                operationStarted.countDown();
                await(releaseOperation);
                return "completed";
            }));
            assertThat(operationStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var rejectedOperation = executionLock.tryRun(() -> "duplicate");
            releaseOperation.countDown();

            assertThat(rejectedOperation).isEmpty();
            assertThat(runningOperation.get(1, TimeUnit.SECONDS)).contains("completed");
            verify(dataSource).getConnection();
        }
    }

    @Test
    void 다른_인스턴스가_DB_잠금을_보유하면_매핑을_실행하지_않는다() throws Exception {
        AtomicBoolean operationExecuted = new AtomicBoolean();
        when(resultSet.getBoolean(1)).thenReturn(false);

        var result = executionLock.tryRun(() -> {
            operationExecuted.set(true);
            return "duplicate";
        });

        assertThat(result).isEmpty();
        assertThat(operationExecuted).isFalse();
        verify(statement).setLong(1, 8_432_026_082_400_003L);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 제한 시간 안에 실행 잠금을 해제하지 못했습니다.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실행 잠금 테스트가 중단되었습니다.", exception);
        }
    }
}
