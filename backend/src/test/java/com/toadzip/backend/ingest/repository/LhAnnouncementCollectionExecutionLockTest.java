package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalDataSource;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementCollectionExecutionLockTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private LhAnnouncementCollectionExecutionLock executionLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        executionLock = new LhAnnouncementCollectionExecutionLock(dataSource);
    }

    @Test
    void 같은_API_수집_잠금의_중복_실행을_거절한다() throws Exception {
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var runningOperation = executor.submit(() -> executionLock.tryRun(
                    ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                    () -> {
                        operationStarted.countDown();
                        await(releaseOperation);
                        return "completed";
                    }
            ));
            assertThat(operationStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var rejectedOperation = executionLock.tryRun(
                    ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                    () -> "duplicate"
            );
            releaseOperation.countDown();

            assertThat(rejectedOperation).isEmpty();
            assertThat(runningOperation.get(1, TimeUnit.SECONDS)).contains("completed");
            verify(dataSource).getConnection();
        }
    }

    @Test
    void 다른_인스턴스가_DB_실행_잠금을_보유하면_수집을_실행하지_않는다() throws Exception {
        AtomicBoolean operationExecuted = new AtomicBoolean();
        when(resultSet.getBoolean(1)).thenReturn(false);

        var result = executionLock.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, () -> {
            operationExecuted.set(true);
            return "duplicate";
        });

        assertThat(result).isEmpty();
        assertThat(operationExecuted).isFalse();
        verify(dataSource).getConnection();
    }

    @Test
    void 상세와_공급_API는_서로_다른_DB_잠금을_사용한다() throws Exception {
        executionLock.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, () -> "detail");
        executionLock.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, () -> "supply");

        verify(statement, times(2)).setLong(1, 8_432_026_082_400_001L);
        verify(statement, times(2)).setLong(1, 8_432_026_082_400_002L);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "테스트 제한 시간 안에 실행 잠금을 해제하지 못했습니다."
                );
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실행 잠금 테스트가 중단되었습니다.", exception);
        }
    }
}
