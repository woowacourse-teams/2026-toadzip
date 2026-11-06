package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.configuration.ExternalDataIngestProperties;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecoveryService;
import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.IngestServiceKeyMissingException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.lh.collection.repository.external.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DataPipelineExecutionServiceTest {

    private static final String RETIRED_REGISTRATION_MESSAGE =
            "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);

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

    @Mock
    private SourceCollectionRecoveryService collectionRecovery;

    private DataPipelineExecutionService service;

    private DataPipelineExecutionQueryService query;

    @BeforeEach
    void setUp() {
        lenient().when(executionLock.tryAcquire())
                .thenReturn(Optional.of(mock(DataPipelineExecutionLock.Lease.class)));
        lenient().doReturn(heartbeatTask).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any()
        );
        service = serviceWith(Runnable::run);
        query = new DataPipelineExecutionQueryService(executionRepository, executionMapper(), recovery(CLOCK));
    }

    private DataPipelineExecutionService serviceWith(Executor executor) {
        return serviceWith(executor, "configured-test-key");
    }

    private DataPipelineExecutionService serviceWith(Executor executor, String configuredKey) {
        var failures = new DataPipelineFailureRecorder(executionStateService, CLOCK);
        return new DataPipelineExecutionService(
                runner,
                executionLock,
                executionStateService,
                recovery(CLOCK),
                new DataPipelineHeartbeat(heartbeatExecutor, executionStateService, failures, CLOCK),
                failures,
                executor,
                CLOCK,
                executionMapper(),
                new ExternalDataIngestProperties(configuredKey, null)
        );
    }

    private DataPipelineExecutionRecovery recovery(Clock clock) {
        return new DataPipelineExecutionRecovery(executionLock, executionStateService, collectionRecovery, clock);
    }

    @Test
    void 지원을_종료한_단건_등록은_잠금과_실행_기록_전에_거절한다() {
        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_REGISTRATION))
                .isInstanceOf(RetiredIngestOperationException.class)
                .hasMessage(RETIRED_REGISTRATION_MESSAGE);

        verify(executionLock, never()).tryAcquire(any());
        verify(executionStateService, never()).create(any(), any(), any());
        verify(runner, never()).run(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {
            "COMPLEX_COLLECTION", "ANNOUNCEMENT_COLLECTION", "ANNOUNCEMENT_SYNC"
    })
    void 서버_수집_키가_없으면_외부_수집은_잠금과_실행_기록_전에_키_설정을_안내하며_거절한다(DataPipelineType type) {
        service = serviceWith(Runnable::run, " ");

        assertThatThrownBy(() -> service.start(type))
                .isInstanceOf(IngestServiceKeyMissingException.class)
                .hasMessageContaining("DATA_GO_KR_SERVICE_KEY");

        assertThat(service.readiness().serviceKeyConfigured()).isFalse();
        verify(executionLock, never()).tryAcquire(any(UUID.class));
        verify(executionStateService, never()).create(any(), any(), any());
        verify(runner, never()).run(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"ANNOUNCEMENT_REFINEMENT", "SH_ANNOUNCEMENT_COLLECTION"})
    void 공공데이터포털을_호출하지_않는_실행은_서버_수집_키_없이도_시작한다(DataPipelineType type) {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        service = serviceWith(Runnable::run, null);

        service.start(type);

        verify(runner).run(eq(type), any());
    }

    @Test
    void 서버_수집_키가_있으면_준비_상태에_설정_여부만_알린다() {
        var readiness = service.readiness();

        assertThat(readiness.serviceKeyConfigured()).isTrue();
        assertThat(readiness.toString()).doesNotContain("configured-test-key");
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"COMPLEX_REFINEMENT", "COMPLEX_SYNC"})
    void 지원을_종료한_단지_정제는_잠금과_실행_기록_전에_거절한다(DataPipelineType type) {
        assertThatThrownBy(() -> service.start(type))
                .isInstanceOf(RetiredIngestOperationException.class)
                .hasMessage("단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.");

        verify(executionLock, never()).tryAcquire(any(UUID.class));
        verify(executionStateService, never()).create(any(), any(), any());
        verify(runner, never()).run(any(), any());
    }

    @Test
    void 단지_이력은_단지_유형만_조회한다() {
        when(executionRepository.findByTypeIn(any(), any())).thenReturn(Page.empty());

        query.history(0, 20, "complex");

        verify(executionRepository).findByTypeIn(
                eq(List.of(DataPipelineType.COMPLEX_COLLECTION, DataPipelineType.COMPLEX_REFINEMENT,
                        DataPipelineType.COMPLEX_SYNC)),
                any()
        );
    }

    @Test
    void 공고_이력은_LH와_함께_SH와_GH_공고_수집도_조회한다() {
        when(executionRepository.findByTypeIn(any(), any())).thenReturn(Page.empty());

        query.history(0, 20, "announcement");

        verify(executionRepository).findByTypeIn(
                eq(List.of(DataPipelineType.ANNOUNCEMENT_COLLECTION, DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineType.ANNOUNCEMENT_REGISTRATION,
                        DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, DataPipelineType.GH_ANNOUNCEMENT_COLLECTION)),
                any()
        );
    }

    @Test
    void 알_수_없는_이력_구분은_조회_전에_거부한다() {
        assertThatThrownBy(() -> query.history(0, 20, "sh"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("단지 또는 공고를 선택해 주세요.");
        verify(executionRepository, never()).findByTypeIn(any(), any());
    }

    @Test
    void 비동기_수집이_실패하면_실패로_기록하고_잠금을_푼다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            throw new IllegalStateException("수집 실패");
        }).when(runner).run(any(), any());
        service.start(DataPipelineType.COMPLEX_COLLECTION);

        assertThat(query.findLatest(DataPipelineType.COMPLEX_COLLECTION).status())
                .isEqualTo(DataPipelineExecutionStatus.FAILED);
        verify(lease).close();
    }

    @Test
    void 실행_ID를_별도_로그_맥락으로_전달하고_요청_traceId를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
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
    void 실행_ID를_실행_맥락으로_전달한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
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
        AtomicReference<Optional<UUID>> observedExecutionId = new AtomicReference<>();
        doAnswer(invocation -> {
            observedExecutionId.set(IngestExecutionScope.currentExecutionId());
            throw new IllegalStateException("테스트 실패");
        }).when(runner).run(any(), any());

        var started = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(observedExecutionId.get()).contains(started.executionId());
    }

    @Test
    void 시작한_작업이_모든_단계를_마치면_완료_상태를_조회한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            UUID executionId = invocation.getArgument(1);
            type.steps().forEach(step -> {
                executionStateService.startStep(executionId, step);
                executionStateService.completeStep(executionId, step, "{\"processedCount\":1}");
            });
            return null;
        }).when(runner).run(any(), any());

        var started = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(started.executionId()).isNotNull();
        assertThat(started.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(started.trigger()).isEqualTo(DataPipelineExecutionTrigger.MANUAL);
        assertThat(started.scheduledAt()).isNull();
        assertThat(started.upstreamExecutionId()).isNull();
        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(status.completedSteps()).hasSize(3);
        verify(lease).close();
        verify(executionStateService, times(3)).startStep(any(), any());
        verify(executionStateService, times(3)).completeStep(any(), any(), any());
        verify(executionStateService).complete(any(), any());
    }

    @Test
    void 다른_애플리케이션_인스턴스에서도_완료된_실행_상태를_조회한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            UUID executionId = invocation.getArgument(1);
            type.steps().forEach(step -> {
                executionStateService.startStep(executionId, step);
                executionStateService.completeStep(executionId, step, "{\"processedCount\":1}");
            });
            return null;
        }).when(runner).run(any(), any());
        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        DataPipelineExecutionQueryService otherInstance = new DataPipelineExecutionQueryService(
                executionRepository, executionMapper(), recovery(CLOCK));

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

        var response = query.find(executionId);

        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.type()).isEqualTo(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
    }

    @Test
    void 존재하지_않는_실행_ID를_조회하면_명시적인_예외를_던진다() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.find(executionId))
                .isInstanceOf(DataPipelineExecutionNotFoundException.class)
                .hasMessageContaining(executionId.toString());
    }

    @Test
    void 다른_파이프라인이_실행_중이면_새_실행을_거부한다() {
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("데이터 수집·정제 작업이 이미 실행 중입니다.");
    }

    @Test
    void 최초_실행_상태를_저장하지_못하면_실행_잠금을_반납한다() {
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        when(executionStateService.create(any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(lease).close();
    }

    @Test
    void 중단_실행_복구에_실패하면_새_실행을_만들지_않고_실행_잠금을_반납한다() {
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        when(executionStateService.recoverInterruptedBefore(any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(executionStateService, never()).create(any(), any(), any());
        verify(lease).close();
    }

    @Test
    void heartbeat가_실행_소유권을_잃으면_실행을_현재_단계의_실패로_끝낸다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        var accepted = serviceWith(task -> { }).start(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
        executionStateService.startStep(accepted.executionId(), DataPipelineStep.MAP_LH_ANNOUNCEMENTS);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatExecutor).scheduleWithFixedDelay(heartbeat.capture(), anyLong(), anyLong(), any());
        doThrow(new IngestOwnershipLostException()).when(lease).verifyHeld();

        heartbeat.getValue().run();

        var status = query.find(accepted.executionId());
        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("LH 공고 정제");
    }

    @Test
    void 부분_실패한_단계와_서버_응답을_실패_상태에_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        String serverResponse = "{\"failedAnnouncementCount\":3}";
        doAnswer(invocation -> {
            UUID executionId = invocation.getArgument(1);
            executionStateService.startStep(executionId, DataPipelineStep.MAP_LH_ANNOUNCEMENTS);
            executionStateService.recordPartialFailure(
                    executionId,
                    DataPipelineStep.MAP_LH_ANNOUNCEMENTS,
                    serverResponse
            );
            throw new DataPipelinePartialFailureException(
                    DataPipelineStep.MAP_LH_ANNOUNCEMENTS,
                    serverResponse
            );
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT);
        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_REFINEMENT);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("LH 공고 정제");
        assertThat(status.failure().serverResponse())
                .isEqualTo(Map.of("failedAnnouncementCount", 3));
        assertThat(status.partiallyFailedSteps()).singleElement().satisfies(failure -> {
            assertThat(failure.step()).isEqualTo(DataPipelineStep.MAP_LH_ANNOUNCEMENTS);
            assertThat(failure.report())
                    .isEqualTo(Map.of("failedAnnouncementCount", 3));
        });
        verify(lease).close();
    }

    @Test
    void 호출_제한으로_건너뛴_단계가_있으면_부분_완료_상태와_사유를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        String serverResponse = "{\"rateLimitedRequestCount\":1}";
        doAnswer(invocation -> {
            UUID executionId = invocation.getArgument(1);
            executionStateService.startStep(executionId, DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);
            executionStateService.skipStep(
                    executionId,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                    "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                    serverResponse
            );
            executionStateService.startStep(executionId, DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);
            executionStateService.completeStep(
                    executionId,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    "{\"collectedSourceRowCount\":1}"
            );
            executionStateService.startStep(executionId, DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
            executionStateService.completeStep(
                    executionId,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS,
                    "{\"collectedSourceRowCount\":1}"
            );
            return null;
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(status.completedSteps()).hasSize(2);
        assertThat(status.completedStepResults()).hasSize(2);
        assertThat(status.completedStepResults().getFirst().report())
                .isEqualTo(Map.of("collectedSourceRowCount", 1));
        assertThat(status.skippedSteps()).singleElement().satisfies(skipped -> {
            assertThat(skipped.stepName()).isEqualTo("LH 공고 목록 수집");
            assertThat(skipped.reason()).contains("호출 제한");
            assertThat(skipped.serverResponse())
                    .isEqualTo(Map.of("rateLimitedRequestCount", 1));
        });
    }

    @Test
    void LH_회로_차단은_실패한_단계와_재실행_안내를_보존한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            UUID executionId = invocation.getArgument(1);
            executionStateService.startStep(executionId, DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);
            throw new LhAnnouncementUnavailableException(
                    "LH 공고 API 장애로 호출을 잠시 중단했습니다. 잠시 후 재실행해주세요.");
        }).when(runner).run(any(), any());

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().stepName()).isEqualTo("LH 공고 목록 수집");
        assertThat(status.failure().message()).contains("LH 공고 API 장애", "재실행");
        verify(lease).close();
    }

    @Test
    void 완료_상태_저장에_실패하면_실패_상태로_종료한다() {
        configureStoredExecution();
        when(executionLock.tryAcquire(any(UUID.class))).thenReturn(Optional.of(lease));
        doAnswer(invocation -> {
            throw new IllegalStateException("final status write failed");
        }).when(executionStateService).complete(any(), any());
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            UUID executionId = invocation.getArgument(1);
            type.steps().forEach(step -> {
                executionStateService.startStep(executionId, step);
                executionStateService.completeStep(executionId, step, "{\"processedCount\":1}");
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
                UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                Instant.parse("2026-09-02T11:00:00Z")
        );
        staleExecution.startStep(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);
        when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenReturn(Optional.of(staleExecution));
        when(executionStateService.recoverInterruptedBefore(any(), any(), any()))
                .thenAnswer(invocation -> {
                    staleExecution.fail(
                            staleExecution.getCurrentStep(),
                            invocation.getArgument(2),
                            null,
                            invocation.getArgument(1)
                    );
                    return 1;
                });

        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(status.failure().message()).contains("중단");
        assertThat(status.failure().stepName())
                .isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG.displayName());
    }

    @Test
    void 오래_갱신되지_않았어도_실행_잠금이_유지되면_복구하지_않는다() {
        DataPipelineExecution activeExecution = DataPipelineExecution.start(
                UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                Instant.parse("2026-09-02T11:00:00Z")
        );
        when(executionRepository.findFirstByTypeOrderByIdDesc(any()))
                .thenReturn(Optional.of(activeExecution));
        when(executionLock.tryAcquire()).thenReturn(Optional.empty());

        var status = query.findLatest(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(status.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        verify(executionStateService, never()).recoverInterruptedBefore(any(), any(), any());
    }

    private void configureStoredExecution() {
        AtomicReference<DataPipelineExecution> savedExecution = new AtomicReference<>();
        lenient().when(executionStateService.create(any(), any(), any()))
                .thenAnswer(invocation -> {
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
