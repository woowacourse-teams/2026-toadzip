package com.toadzip.backend.ingest.pipeline.service;

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

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
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
        lenient().doReturn(heartbeatTask).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any()
        );
        service = serviceWith(Runnable::run);
    }

    private DataPipelineExecutionService serviceWith(Executor executor) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
        return new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                executionStateService,
                executor,
                heartbeatExecutor,
                clock,
                executionMapper()
        );
    }

    @Test
    void 실행_ID를_별도_로그_맥락으로_전달하고_요청_traceId를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        service = serviceWith(task -> {
            Thread worker = Thread.ofPlatform().start(task);
            try {
                worker.join();
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        AtomicReference<String> observedExecutionId = new AtomicReference<>();
        AtomicReference<String> observedTraceId = new AtomicReference<>();
        doAnswer(invocation -> {
            observedExecutionId.set(MDC.get("executionId"));
            observedTraceId.set(MDC.get("traceId"));
            throw new IllegalStateException("테스트 실패");
        }).when(runner).run(any(), any());
        MDC.put("traceId", "request-trace");
        MDC.put("executionId", "previous-execution");
        try {
            var started = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
            assertThat(observedExecutionId.get()).isEqualTo(started.executionId().toString());
            assertThat(observedTraceId.get()).isEqualTo("request-trace");
            assertThat(MDC.get("traceId")).isEqualTo("request-trace");
            assertThat(MDC.get("executionId")).isEqualTo("previous-execution");
        }
        finally {
            MDC.clear();
        }
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
                listener.completed(step, "{\"processedCount\":1}");
            });
            return null;
        }).when(runner).run(any(), any());

        var started = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(started.executionId()).isNotNull();
        assertThat(started.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(status.completedSteps()).hasSize(4);
        verify(lease).close();
        verify(executionStateService, times(4)).startStep(any(), any());
        verify(executionStateService, times(4)).completeStep(any(), any(), any());
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
                listener.completed(step, "{\"processedCount\":1}");
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
    void 실행_ID로_저장된_결과를_조회한다() {
        UUID executionId = UUID.randomUUID();
        DataPipelineExecution execution = DataPipelineExecution.start(
                executionId,
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                Instant.parse("2026-09-02T12:00:00Z")
        );
        when(executionRepository.findByExecutionId(executionId))
                .thenReturn(Optional.of(execution));

        var response = service.find(executionId);

        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.type()).isEqualTo(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
    }

    @Test
    void 존재하지_않는_실행_ID를_조회하면_명시적인_예외를_던진다() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(executionId))
                .isInstanceOf(DataPipelineExecutionNotFoundException.class)
                .hasMessageContaining(executionId.toString());
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
        String serverResponse = "{\"failedSourceRowCount\":3}";
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
            listener.partiallyFailed(
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                    serverResponse
            );
            throw new DataPipelinePartialFailureException(
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                    serverResponse
            );
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_REFINEMENT);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("마이홈 공고 정제");
        assertThat(status.failure().serverResponse())
                .isEqualTo(java.util.Map.of("failedSourceRowCount", 3));
        assertThat(status.partiallyFailedSteps()).singleElement().satisfies(failure -> {
            assertThat(failure.step()).isEqualTo(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
            assertThat(failure.report())
                    .isEqualTo(java.util.Map.of("failedSourceRowCount", 3));
        });
        verify(lease).close();
    }

    @Test
    void 호출_제한으로_건너뛴_단계가_있으면_부분_완료_상태와_사유를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        String serverResponse = "{\"rateLimitedRequestCount\":1}";
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
            listener.skipped(
                    DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                    "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                    serverResponse
            );
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);
            listener.completed(
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                    "{\"collectedSourceRowCount\":1}"
            );
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);
            listener.completed(
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    "{\"collectedSourceRowCount\":1}"
            );
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
            listener.completed(
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS,
                    "{\"collectedSourceRowCount\":1}"
            );
            return null;
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(status.completedSteps()).hasSize(3);
        assertThat(status.completedStepResults()).hasSize(3);
        assertThat(status.completedStepResults().getFirst().report())
                .isEqualTo(java.util.Map.of("collectedSourceRowCount", 1));
        assertThat(status.skippedSteps()).singleElement().satisfies(skipped -> {
            assertThat(skipped.stepName()).isEqualTo("마이홈 공고 수집");
            assertThat(skipped.reason()).contains("호출 제한");
            assertThat(skipped.serverResponse())
                    .isEqualTo(java.util.Map.of("rateLimitedRequestCount", 1));
        });
    }

    @Test
    void LH_회로_차단은_실패한_단계와_재실행_안내를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
            listener.completed(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS, "{}");
            listener.started(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);
            throw new com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException(
                    "LH 공고 API 장애로 호출을 잠시 중단했습니다. 잠시 후 재실행해주세요.");
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("LH 공고 목록 수집");
        assertThat(status.failure().message()).contains("LH 공고 API 장애", "재실행");
        verify(lease).close();
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
                listener.completed(step, "{\"processedCount\":1}");
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
        staleExecution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
        when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenReturn(Optional.of(staleExecution));
        when(executionStateService.recoverInterrupted(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    staleExecution.fail(
                            staleExecution.getCurrentStep(),
                            invocation.getArgument(3),
                            null,
                            invocation.getArgument(2)
                    );
                    return true;
                });
        when(executionRepository.findByExecutionId(any())).thenReturn(Optional.of(staleExecution));

        var status = service.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().message()).contains("중단");
        assertThat(status.failure().stepName())
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS.displayName());
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
        verify(executionStateService, never()).recoverInterrupted(any(), any(), any(), any());
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
            savedExecution.get().completeStep(
                    invocation.getArgument(1),
                    invocation.getArgument(2)
            );
            return null;
        }).when(executionStateService).completeStep(any(), any(), any());
        lenient().doAnswer(invocation -> {
            savedExecution.get().skipStep(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)
            );
            return null;
        }).when(executionStateService).skipStep(any(), any(), any(), any());
        lenient().doAnswer(invocation -> {
            savedExecution.get().recordPartialFailure(
                    invocation.getArgument(1),
                    invocation.getArgument(2)
            );
            return null;
        }).when(executionStateService).recordPartialFailure(any(), any(), any());
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
