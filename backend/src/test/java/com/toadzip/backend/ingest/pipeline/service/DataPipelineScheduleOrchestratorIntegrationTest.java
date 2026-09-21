package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineSchedulerProperties;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferralReason;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineScheduleDeferralRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DataPipelineScheduleOrchestratorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T05:15:00Z");
    private static final Instant ANNOUNCEMENT_SLOT = Instant.parse("2026-09-21T03:00:00Z");
    private static final Instant NEXT_ANNOUNCEMENT_SLOT =
            Instant.parse("2026-09-21T09:00:00Z");
    private static final DataPipelineSchedulerProperties PROPERTIES =
            new DataPipelineSchedulerProperties(
                    false,
                    60_000,
                    Duration.ofHours(6),
                    "Asia/Seoul",
                    DayOfWeek.MONDAY,
                    LocalTime.of(3, 0)
            );

    @Autowired
    private DataPipelineExecutionRepository executionRepository;

    @Autowired
    private DataPipelineScheduleDeferralRepository deferralRepository;

    @Autowired
    private DataPipelineExecutionStateService executionStateService;

    @Autowired
    private DataPipelineExecutionLock executionLock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataPipelineExecutionMapper executionMapper;

    @Autowired
    private DataPipelineScheduleDeferralService deferralService;

    private DataPipelineRunner runner;
    private DataPipelineScheduleOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        deferralRepository.deleteAll();
        executionRepository.deleteAll();
        runner = mock(DataPipelineRunner.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        doReturn(heartbeatTask).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any()
        );
        DataPipelineExecutionService executionService = new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                executionStateService,
                Runnable::run,
                heartbeatExecutor,
                clock,
                executionMapper
        );
        orchestrator = new DataPipelineScheduleOrchestrator(
                executionService,
                executionRepository,
                new DataPipelineScheduleSlotPolicy(PROPERTIES),
                deferralService,
                PROPERTIES,
                new SimpleMeterRegistry(),
                clock
        );
    }

    @Test
    void 정상_스케줄은_PostgreSQL에_수집과_정제를_한_번씩_연결한다() {
        completeEveryPipeline();

        orchestrator.runOnce(NOW);
        orchestrator.runOnce(NOW);
        orchestrator.runOnce(NOW);

        List<DataPipelineExecution> executions = executionRepository.findAll();
        assertThat(executions)
                .extracting(DataPipelineExecution::getType)
                .containsExactlyInAnyOrder(
                        DataPipelineType.COMPLEX_COLLECTION,
                        DataPipelineType.COMPLEX_REFINEMENT,
                        DataPipelineType.ANNOUNCEMENT_COLLECTION,
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT
                );
        assertThat(executions)
                .extracting(DataPipelineExecution::getStatus)
                .containsOnly(DataPipelineExecutionStatus.COMPLETED);
        assertRefinementLinked(
                executions,
                DataPipelineType.COMPLEX_COLLECTION,
                DataPipelineType.COMPLEX_REFINEMENT
        );
        assertRefinementLinked(
                executions,
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineType.ANNOUNCEMENT_REFINEMENT
        );
    }

    @Test
    void 슬롯_경계를_넘겨_완료된_수집의_정제를_현재_슬롯보다_먼저_연결한다() {
        completeEveryPipeline();
        UUID collectionId = saveCompletedScheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        );

        orchestrator.runOnce(NEXT_ANNOUNCEMENT_SLOT.plusSeconds(1));

        assertThat(executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        collectionId
                )).isPresent();
        assertThat(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                NEXT_ANNOUNCEMENT_SLOT
        )).isEmpty();

        orchestrator.runOnce(NEXT_ANNOUNCEMENT_SLOT.plusSeconds(1));

        assertThat(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                NEXT_ANNOUNCEMENT_SLOT
        )).isPresent();
    }

    @Test
    void 여러_이전_슬롯의_미연결_정제를_오래된_수집부터_처리한다() {
        completeEveryPipeline();
        UUID oldestCollectionId = saveCompletedScheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT.minus(PROPERTIES.announcementInterval())
        );
        UUID latestCollectionId = saveCompletedScheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        );

        orchestrator.runOnce(NEXT_ANNOUNCEMENT_SLOT.plusSeconds(1));

        assertThat(executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        oldestCollectionId
                )).isPresent();
        assertThat(executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        latestCollectionId
                )).isEmpty();

        orchestrator.runOnce(NEXT_ANNOUNCEMENT_SLOT.plusSeconds(1));

        assertThat(executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        latestCollectionId
                )).isPresent();
    }

    @Test
    void 다른_인스턴스의_PostgreSQL_advisory_lock_충돌은_지연으로_저장한다() {
        completeEveryPipeline();
        DataPipelineExecutionLock competingInstanceLock = new DataPipelineExecutionLock(dataSource);

        try (DataPipelineExecutionLock.Lease ignored = competingInstanceLock
                .tryAcquire()
                .orElseThrow()) {
            orchestrator.runOnce(NOW);
        }

        assertThat(executionRepository.findAll()).isEmpty();
        assertThat(deferralRepository.findAllByOrderByScheduleAsc())
                .hasSize(2)
                .allSatisfy(deferral -> {
                    assertThat(deferral.getReason())
                            .isEqualTo(DataPipelineScheduleDeferralReason.EXECUTION_IN_PROGRESS);
                    assertThat(deferral.getObservedAt()).isEqualTo(NOW);
                    assertThat(deferral.getNextRetryAt()).isEqualTo(NOW.plusSeconds(60));
                    assertThat(deferral.isActive()).isTrue();
                });

        orchestrator.runOnce(NOW);

        assertThat(deferralRepository.findAllByOrderByScheduleAsc())
                .hasSize(2)
                .allSatisfy(deferral -> {
                    assertThat(deferral.isActive()).isFalse();
                    assertThat(deferral.getResolvedAt()).isEqualTo(NOW);
                });
    }

    @Test
    void 실행_중인_수집은_다음_폴링_시각과_함께_저장한다() {
        UUID executionId = UUID.randomUUID();
        executionStateService.create(
                executionId,
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                NOW,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null
        );

        orchestrator.runOnce(NOW);

        assertThat(deferralRepository.findById(DataPipelineSchedule.ANNOUNCEMENT))
                .get()
                .satisfies(deferral -> {
                    assertThat(deferral.getDetail()).isEqualTo("RUNNING");
                    assertThat(deferral.getNextRetryAt()).isEqualTo(NOW.plusSeconds(60));
                });
    }

    @Test
    void 건너뛴_수집은_정제를_연결하지_않고_지연_상태를_저장한다() {
        completeWithSkippedAnnouncementCollection();

        orchestrator.runOnce(NOW);
        orchestrator.runOnce(NOW);

        assertThat(executionRepository.findFirstByTypeOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_REFINEMENT
        )).isEmpty();
        assertThat(deferralRepository.findById(DataPipelineSchedule.ANNOUNCEMENT))
                .get()
                .satisfies(deferral -> {
                    assertThat(deferral.getReason()).isEqualTo(
                            DataPipelineScheduleDeferralReason.COLLECTION_NOT_COMPLETED
                    );
                    assertThat(deferral.getDetail()).isEqualTo("COMPLETED_WITH_SKIPS");
                    assertThat(deferral.getNextRetryAt()).isNull();
                    assertThat(deferral.isActive()).isTrue();
                });
    }

    @Test
    void 실패한_수집은_정제를_연결하지_않고_지연_상태를_저장한다() {
        failAnnouncementCollection();

        orchestrator.runOnce(NOW);
        orchestrator.runOnce(NOW);

        assertThat(executionRepository.findFirstByTypeOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION
        )).get().extracting(DataPipelineExecution::getStatus)
                .isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(executionRepository.findFirstByTypeOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_REFINEMENT
        )).isEmpty();
        assertThat(deferralRepository.findById(DataPipelineSchedule.ANNOUNCEMENT))
                .get()
                .satisfies(deferral -> {
                    assertThat(deferral.getReason()).isEqualTo(
                            DataPipelineScheduleDeferralReason.COLLECTION_NOT_COMPLETED
                    );
                    assertThat(deferral.getDetail()).isEqualTo("FAILED");
                    assertThat(deferral.getNextRetryAt()).isNull();
                    assertThat(deferral.isActive()).isTrue();
                });
    }

    @Test
    void 기존_수동_이력_뒤의_누락_슬롯은_복구_실행으로_저장한다() {
        completeEveryPipeline();
        saveCompletedManualExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT.minusSeconds(60)
        );

        orchestrator.runOnce(NOW);

        DataPipelineExecution recovery = executionRepository
                .findFirstByTypeAndScheduledAtOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_COLLECTION,
                        ANNOUNCEMENT_SLOT
                )
                .orElseThrow();
        assertThat(recovery.getExecutionTrigger())
                .isEqualTo(DataPipelineExecutionTrigger.RECOVERY);
    }

    private void completeEveryPipeline() {
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            type.steps().forEach(step -> {
                listener.started(step);
                listener.completed(step, "{}");
            });
            return null;
        }).when(runner).run(any(), any());
    }

    private void completeWithSkippedAnnouncementCollection() {
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            type.steps().forEach(step -> {
                listener.started(step);
                if (type == DataPipelineType.ANNOUNCEMENT_COLLECTION
                        && step == type.steps().getFirst()) {
                    listener.skipped(step, "외부 API 호출 제한", "{}");
                    return;
                }
                listener.completed(step, "{}");
            });
            return null;
        }).when(runner).run(any(), any());
    }

    private void failAnnouncementCollection() {
        doAnswer(invocation -> {
            DataPipelineType type = invocation.getArgument(0);
            DataPipelineProgressListener listener = invocation.getArgument(1);
            if (type == DataPipelineType.ANNOUNCEMENT_COLLECTION) {
                listener.started(type.steps().getFirst());
                throw new IllegalStateException("외부 수집 실패");
            }
            type.steps().forEach(step -> {
                listener.started(step);
                listener.completed(step, "{}");
            });
            return null;
        }).when(runner).run(any(), any());
    }

    private void saveCompletedManualExecution(DataPipelineType type, Instant startedAt) {
        UUID executionId = UUID.randomUUID();
        executionStateService.create(executionId, type, startedAt);
        type.steps().forEach(step -> {
            executionStateService.startStep(executionId, step);
            executionStateService.completeStep(executionId, step, "{}");
        });
        executionStateService.complete(executionId, startedAt.plusSeconds(1));
    }

    private UUID saveCompletedScheduledExecution(
            DataPipelineType type,
            Instant scheduledAt
    ) {
        UUID executionId = UUID.randomUUID();
        executionStateService.create(
                executionId,
                type,
                scheduledAt,
                DataPipelineExecutionTrigger.SCHEDULED,
                scheduledAt,
                null
        );
        type.steps().forEach(step -> {
            executionStateService.startStep(executionId, step);
            executionStateService.completeStep(executionId, step, "{}");
        });
        executionStateService.complete(executionId, scheduledAt.plusSeconds(1));
        return executionId;
    }

    private void assertRefinementLinked(
            List<DataPipelineExecution> executions,
            DataPipelineType collectionType,
            DataPipelineType refinementType
    ) {
        DataPipelineExecution collection = execution(executions, collectionType);
        DataPipelineExecution refinement = execution(executions, refinementType);
        assertThat(refinement.getUpstreamExecutionId())
                .isEqualTo(collection.getExecutionId());
    }

    private DataPipelineExecution execution(
            List<DataPipelineExecution> executions,
            DataPipelineType type
    ) {
        return executions.stream()
                .filter(execution -> execution.getType() == type)
                .findFirst()
                .orElseThrow();
    }
}
