package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    @Mock
    private DataPipelineExecutionStateService executionStateService;

    @Mock
    private ScheduledExecutorService heartbeatExecutor;

    @Mock
    private ScheduledFuture<?> heartbeatTask;

    private DataPipelineExecutionService service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
        lenient().doReturn(heartbeatTask).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any()
        );
        service = new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                executionStateService,
                directExecutor,
                heartbeatExecutor,
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

        var started = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(started.executionId()).isNotNull();
        assertThat(started.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(status.completedSteps()).hasSize(3);
        verify(lease).close();
        verify(executionStateService, times(3)).startStep(any(), any());
        verify(executionStateService, times(3)).completeStep(any(), any());
        verify(executionStateService).complete(any(), any());
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
        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        DataPipelineExecutionService otherInstance = new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                executionStateService,
                Runnable::run,
                heartbeatExecutor,
                Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC),
                executionMapper()
        );

        var status = otherInstance.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
    }

    @Test
    void 다른_파이프라인이_실행_중이면_새_실행을_거부한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("데이터 수집·정제 작업이 이미 실행 중입니다.");
    }

    @Test
    void 최초_실행_상태를_저장하지_못하면_실행_잠금을_반납한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        when(executionStateService.create(any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION))
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

        service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_REFINEMENT);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("마이홈 공고 정제");
        assertThat(status.failure().serverResponse()).isEqualTo(serverResponse);
        verify(lease).close();
    }

    @Test
    void 호출_제한으로_건너뛴_단계가_있으면_부분_완료_상태와_사유를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        java.util.Map<String, Integer> report = java.util.Map.of("rateLimitedRequestCount", 1);
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
            listener.skipped(
                    DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                    "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                    report
            );
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);
            listener.completed(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
            listener.completed(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
            return null;
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(status.completedSteps()).hasSize(2);
        assertThat(status.skippedSteps()).singleElement().satisfies(skipped -> {
            assertThat(skipped.stepName()).isEqualTo("마이홈 공고 수집");
            assertThat(skipped.reason()).contains("호출 제한");
            assertThat(skipped.serverResponse()).isEqualTo(report);
        });
    }

    @Test
    void 완료_상태_저장에_실패하면_실패_상태로_종료한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            throw new IllegalStateException("final status write failed");
        }).when(executionStateService).complete(any(), any());
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            type.steps().forEach(step -> {
                listener.started(step);
                listener.completed(step);
            });
            return null;
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        verify(executionStateService).fail(
                any(),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.isNull(),
                any()
        );
    }

    @Test
    void 오래_갱신되지_않은_실행은_실패_상태로_복구한다() {
        DataPipelineExecution staleExecution = DataPipelineExecution.start(
                java.util.UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                Instant.parse("2026-09-02T11:00:00Z")
        );
        when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenReturn(Optional.of(staleExecution));
        doAnswer(invocation -> {
            staleExecution.fail(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)
            );
            return null;
        }).when(executionStateService).fail(
                any(),
                org.mockito.ArgumentMatchers.nullable(DataPipelineStep.class),
                any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any()
        );
        when(executionRepository.findByExecutionId(any())).thenReturn(Optional.of(staleExecution));

        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().message()).contains("중단");
    }

    @Test
    void 오래_갱신되지_않았어도_실행_잠금이_유지되면_복구하지_않는다() {
        DataPipelineExecution activeExecution = DataPipelineExecution.start(
                java.util.UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                Instant.parse("2026-09-02T11:00:00Z")
        );
        when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenReturn(Optional.of(activeExecution));
        when(executionLock.isHeld()).thenReturn(true);

        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        verify(executionStateService, never()).fail(any(), any(), any(), any(), any());
    }

    private void configureStoredExecution() {
        AtomicReference<DataPipelineExecution> savedExecution = new AtomicReference<>();
        lenient().when(executionStateService.create(any(), any(), any())).thenAnswer(invocation -> {
            DataPipelineExecution execution = DataPipelineExecution.start(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)
            );
            savedExecution.set(execution);
            return execution;
        });
        lenient().doAnswer(invocation -> {
            savedExecution.get().startStep(invocation.getArgument(1));
            return null;
        }).when(executionStateService).startStep(any(), any());
        lenient().doAnswer(invocation -> {
            savedExecution.get().completeStep(invocation.getArgument(1));
            return null;
        }).when(executionStateService).completeStep(any(), any());
        lenient().doAnswer(invocation -> {
            savedExecution.get().skipStep(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)
            );
            return null;
        }).when(executionStateService).skipStep(any(), any(), any(), any());
        lenient().doAnswer(invocation -> {
            savedExecution.get().complete(invocation.getArgument(1));
            return null;
        }).when(executionStateService).complete(any(), any());
        lenient().doAnswer(invocation -> {
            DataPipelineExecution execution = savedExecution.get();
            if (execution != null && execution.isRunning()) {
                execution.fail(
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4)
                );
            }
            return null;
        }).when(executionStateService).fail(
                any(),
                org.mockito.ArgumentMatchers.nullable(DataPipelineStep.class),
                any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                any()
        );
        lenient().when(executionStateService.findCurrentStep(any())).thenAnswer(
                invocation -> savedExecution.get().getCurrentStep()
        );
        lenient().when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedExecution.get()));
        lenient().when(executionRepository.findByExecutionId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedExecution.get()));
    }

    private DataPipelineExecutionMapper executionMapper() {
        return new DataPipelineExecutionMapper(JsonMapper.builder().build());
    }
}
