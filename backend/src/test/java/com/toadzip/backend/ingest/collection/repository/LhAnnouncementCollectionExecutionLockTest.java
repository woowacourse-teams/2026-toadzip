package com.toadzip.backend.ingest.collection.repository;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.LH_ANNOUNCEMENT_COLLECTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private IngestOperationLock executionLock;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        executionLock = new IngestOperationLock(dataSource);
    }

    @Test
    void 다른_인스턴스가_DB_실행_잠금을_보유하면_수집을_실행하지_않는다() throws Exception {
        AtomicBoolean operationExecuted = new AtomicBoolean();
        when(resultSet.getBoolean(1)).thenReturn(false);

        var result = executionLock.tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> {
            operationExecuted.set(true);
            return "duplicate";
        });

        assertThat(result).isEmpty();
        assertThat(operationExecuted).isFalse();
        verify(dataSource).getConnection();
    }

    @Test
    void 상세와_공급_API는_같은_DB_잠금을_사용한다() throws Exception {
        executionLock.tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> "detail");
        executionLock.tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> "supply");

        verify(statement, times(4)).setLong(1, 8_432_026_082_400_001L);
    }

}
