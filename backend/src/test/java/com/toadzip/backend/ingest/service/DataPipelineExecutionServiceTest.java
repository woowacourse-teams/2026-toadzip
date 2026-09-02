package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DataPipelineExecutionServiceTest {

    @Mock
    private DataPipelineRunner runner;

    @Mock
    private DataPipelineExecutionLock executionLock;

    @Mock
    private DataPipelineExecutionLock.Lease lease;

    @Mock
    private DataPipelineExecutionRepository executionRepository;

    private DataPipelineExecutionService service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
        service = new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                directExecutor,
                clock,
                executionMapper()
        );
    }

    @Test
    void 시작한_작업이_모든_단계를_마치면_완료_상태를_조회한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            type.steps().forEach(step -> {
                listener.started(step);
                listener.completed(step);
            });
            return null;
        }).when(runner).run(any(), any());

        var started = service.start(DataPipelineType.COLLECTION);
        var status = service.findLatest(DataPipelineType.COLLECTION);

        assertThat(started.executionId()).isNotNull();
        assertThat(started.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(status.completedSteps()).hasSize(5);
        verify(lease).close();
        verify(executionRepository, times(12)).saveAndFlush(any());
    }

    @Test
    void 다른_애플리케이션_인스턴스에서도_완료된_실행_상태를_조회한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            type.steps().forEach(step -> {
                listener.started(step);
                listener.completed(step);
            });
            return null;
        }).when(runner).run(any(), any());
        service.start(DataPipelineType.COLLECTION);
        DataPipelineExecutionService otherInstance = new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC),
                executionMapper()
        );

        var status = otherInstance.findLatest(DataPipelineType.COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
    }

    @Test
    void 다른_파이프라인이_실행_중이면_새_실행을_거부한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(DataPipelineType.REFINEMENT))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("데이터 수집·정제 작업이 이미 실행 중입니다.");
    }

    @Test
    void 최초_실행_상태를_저장하지_못하면_실행_잠금을_반납한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        when(executionRepository.saveAndFlush(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.start(DataPipelineType.COLLECTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(lease).close();
    }

    @Test
    void 부분_실패한_단계와_서버_응답을_실패_상태에_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        Object serverResponse = java.util.Map.of("failedSourceRowCount", 3);
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
            throw new DataPipelinePartialFailureException(
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                    serverResponse
            );
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.REFINEMENT);
        var status = service.findLatest(DataPipelineType.REFINEMENT);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("마이홈 공고 정제");
        assertThat(status.failure().serverResponse()).isEqualTo(serverResponse);
        verify(lease).close();
    }

    private void configureStoredExecution() {
        AtomicReference<DataPipelineExecution> savedExecution = new AtomicReference<>();
        when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            DataPipelineExecution execution = invocation.getArgument(0);
            savedExecution.set(execution);
            return execution;
        });
        when(executionRepository.findFirstByTypeOrderByStartedAtDescIdDesc(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedExecution.get()));
    }

    private DataPipelineExecutionMapper executionMapper() {
        return new DataPipelineExecutionMapper(JsonMapper.builder().build());
    }
}
