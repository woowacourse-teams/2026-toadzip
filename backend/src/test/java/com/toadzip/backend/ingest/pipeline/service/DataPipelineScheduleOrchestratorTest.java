package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineSchedulerProperties;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.DayOfWeek;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPipelineScheduleOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-09-21T05:15:00Z");
    private static final Instant ANNOUNCEMENT_SLOT = Instant.parse("2026-09-21T03:00:00Z");

    @Mock
    private DataPipelineExecutionService executionService;

    @Mock
    private DataPipelineExecutionRepository executionRepository;

    private SimpleMeterRegistry meterRegistry;
    private DataPipelineScheduleOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        DataPipelineSchedulerProperties properties = new DataPipelineSchedulerProperties(
                false,
                60_000,
                Duration.ofHours(6),
                "Asia/Seoul",
                DayOfWeek.MONDAY,
                LocalTime.of(3, 0)
        );
        meterRegistry = new SimpleMeterRegistry();
        orchestrator = new DataPipelineScheduleOrchestrator(
                executionService,
                executionRepository,
                new DataPipelineScheduleSlotPolicy(properties),
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        lenient().when(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(executionRepository.findFirstByTypeAndScheduledAtBeforeOrderByScheduledAtDesc(
                any(), any()
        )).thenReturn(Optional.empty());
        lenient().when(executionRepository.findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                any(), any()
        )).thenReturn(Optional.empty());
        lenient().when(executionService.start(any(), any(), any(), any()))
                .thenAnswer(invocation -> accepted(
                        invocation.getArgument(0, DataPipelineType.class),
                        invocation.getArgument(1, DataPipelineExecutionTrigger.class),
                        invocation.getArgument(2, Instant.class),
                        invocation.getArgument(3, UUID.class)
                ));
    }

    @Test
    void 현재_슬롯에_수집_실행이_없으면_정기_수집을_한_번_시작한다() {
        orchestrator.runOnce(NOW);

        verify(executionService).start(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null
        );
        verify(executionService).start(
                DataPipelineType.COMPLEX_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                Instant.parse("2026-09-20T18:00:00Z"),
                null
        );
    }

    @Test
    void 이전_슬롯이_빠져_있으면_현재_슬롯을_복구_실행으로_한_번_보충한다() {
        DataPipelineExecution previous = scheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                UUID.randomUUID(),
                Instant.parse("2026-09-20T15:00:00Z")
        );
        when(executionRepository.findFirstByTypeAndScheduledAtBeforeOrderByScheduledAtDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        )).thenReturn(Optional.of(previous));

        orchestrator.runOnce(NOW);

        verify(executionService).start(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.RECOVERY,
                ANNOUNCEMENT_SLOT,
                null
        );
    }

    @Test
    void 수집이_완료된_뒤에만_상위_실행_ID를_연결해_정제를_시작한다() {
        UUID collectionId = UUID.randomUUID();
        DataPipelineExecution collection = scheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                collectionId,
                ANNOUNCEMENT_SLOT
        );
        complete(collection);
        when(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        )).thenReturn(Optional.of(collection));
        when(executionService.find(collectionId)).thenReturn(accepted(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null,
                collectionId,
                DataPipelineExecutionStatus.COMPLETED
        ));

        orchestrator.runOnce(NOW);

        verify(executionService).start(
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                collectionId
        );
    }

    @Test
    void 수집이_실패하거나_건너뛰면_정제를_자동_시작하지_않는다() {
        UUID collectionId = UUID.randomUUID();
        DataPipelineExecution collection = scheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                collectionId,
                ANNOUNCEMENT_SLOT
        );
        when(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        )).thenReturn(Optional.of(collection));
        when(executionService.find(collectionId)).thenReturn(accepted(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null,
                collectionId,
                DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS
        ));

        orchestrator.runOnce(NOW);

        verify(executionService, never()).start(
                eq(DataPipelineType.ANNOUNCEMENT_REFINEMENT),
                any(),
                any(),
                any()
        );
        assertThat(meterRegistry.get("ingest.scheduler.deferred").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 수집이_실패해도_정제를_자동_시작하지_않는다() {
        UUID collectionId = UUID.randomUUID();
        DataPipelineExecution collection = scheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                collectionId,
                ANNOUNCEMENT_SLOT
        );
        when(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        )).thenReturn(Optional.of(collection));
        when(executionService.find(collectionId)).thenReturn(accepted(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null,
                collectionId,
                DataPipelineExecutionStatus.FAILED
        ));

        orchestrator.runOnce(NOW);

        verify(executionService, never()).start(
                eq(DataPipelineType.ANNOUNCEMENT_REFINEMENT),
                any(),
                any(),
                any()
        );
    }

    @Test
    void 같은_슬롯을_다시_확인해도_이미_시작한_수집을_중복_시작하지_않는다() {
        DataPipelineExecution started = scheduledExecution(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                UUID.randomUUID(),
                ANNOUNCEMENT_SLOT
        );
        when(executionRepository.findFirstByTypeAndScheduledAtOrderByIdDesc(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                ANNOUNCEMENT_SLOT
        )).thenReturn(Optional.empty(), Optional.of(started));
        when(executionService.find(started.getExecutionId())).thenReturn(accepted(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null,
                started.getExecutionId(),
                DataPipelineExecutionStatus.RUNNING
        ));

        orchestrator.runOnce(NOW);
        orchestrator.runOnce(NOW);

        verify(executionService, times(1)).start(
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionTrigger.SCHEDULED,
                ANNOUNCEMENT_SLOT,
                null
        );
    }

    @Test
    void 다른_실행이_잠금을_가지고_있으면_예외를_전파하지_않고_지연으로_기록한다() {
        doThrow(new IngestAlreadyRunningException("실행 중"))
                .when(executionService)
                .start(
                        eq(DataPipelineType.ANNOUNCEMENT_COLLECTION),
                        any(),
                        eq(ANNOUNCEMENT_SLOT),
                        eq(null)
                );

        orchestrator.runOnce(NOW);

        assertThat(meterRegistry.get("ingest.scheduler.deferred").counter().count())
                .isEqualTo(1);
    }

    private DataPipelineExecution scheduledExecution(
            DataPipelineType type,
            UUID executionId,
            Instant scheduledAt
    ) {
        return DataPipelineExecution.start(
                executionId,
                type,
                scheduledAt,
                DataPipelineExecutionTrigger.SCHEDULED,
                scheduledAt,
                null
        );
    }

    private void complete(DataPipelineExecution execution) {
        execution.getType().steps().forEach(step -> {
            execution.startStep(step);
            execution.completeStep(step, "{}");
        });
        execution.complete(ANNOUNCEMENT_SLOT.plusSeconds(10));
    }

    private DataPipelineExecutionResponse accepted(
            DataPipelineType type,
            DataPipelineExecutionTrigger trigger,
            Instant scheduledAt,
            UUID upstreamExecutionId
    ) {
        return accepted(
                type,
                trigger,
                scheduledAt,
                upstreamExecutionId,
                UUID.randomUUID(),
                DataPipelineExecutionStatus.RUNNING
        );
    }

    private DataPipelineExecutionResponse accepted(
            DataPipelineType type,
            DataPipelineExecutionTrigger trigger,
            Instant scheduledAt,
            UUID upstreamExecutionId,
            UUID executionId,
            DataPipelineExecutionStatus status
    ) {
        return new DataPipelineExecutionResponse(
                executionId,
                type,
                trigger,
                scheduledAt,
                upstreamExecutionId,
                status,
                null,
                null,
                0,
                type.steps().size(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                scheduledAt,
                null
        );
    }
}
