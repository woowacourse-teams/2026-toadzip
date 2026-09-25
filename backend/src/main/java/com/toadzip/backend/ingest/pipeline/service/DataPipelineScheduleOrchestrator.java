package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineSchedulerProperties;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferralReason;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleStage;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "ingest.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class DataPipelineScheduleOrchestrator {

    private final DataPipelineExecutionService executionService;
    private final DataPipelineExecutionRepository executionRepository;
    private final DataPipelineScheduleSlotPolicy slotPolicy;
    private final DataPipelineScheduleDeferralService deferralService;
    private final DataPipelineSchedulerProperties schedulerProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<DataPipelineSchedule, String> deferredObservations =
            new EnumMap<>(DataPipelineSchedule.class);

    public DataPipelineScheduleOrchestrator(
            DataPipelineExecutionService executionService,
            DataPipelineExecutionRepository executionRepository,
            DataPipelineScheduleSlotPolicy slotPolicy,
            DataPipelineScheduleDeferralService deferralService,
            DataPipelineSchedulerProperties schedulerProperties,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.executionService = executionService;
        this.executionRepository = executionRepository;
        this.slotPolicy = slotPolicy;
        this.deferralService = deferralService;
        this.schedulerProperties = schedulerProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${ingest.scheduler.poll-interval-millis:60000}",
            scheduler = "dataPipelineScheduleTaskScheduler"
    )
    public void runScheduledCycle() {
        runOnce(clock.instant());
    }

    void runOnce(Instant now) {
        for (DataPipelineSchedule schedule : DataPipelineSchedule.values()) {
            try {
                process(schedule, now);
            }
            catch (RuntimeException exception) {
                meterRegistry.counter(
                        "ingest.scheduler.failed",
                        "schedule", schedule.name()
                ).increment();
                log.error(
                        "event=ingest.schedule.failed schedule={} result=unexpected_error",
                        schedule,
                        exception
                );
            }
        }
    }

    private void process(DataPipelineSchedule schedule, Instant now) {
        Instant scheduledAt = slotPolicy.currentSlot(schedule, now);
        DataPipelineExecution pendingCollection = findPendingCollection(schedule, scheduledAt);
        if (pendingCollection != null) {
            startRefinement(
                    schedule,
                    pendingCollection.getScheduledAt(),
                    executionService.find(pendingCollection.getExecutionId())
            );
            return;
        }
        DataPipelineExecution collection = executionRepository
                .findFirstByTypeAndScheduledAtOrderByIdDesc(
                        schedule.collectionType(),
                        scheduledAt
                )
                .orElse(null);
        if (collection == null) {
            startCollection(schedule, scheduledAt);
            return;
        }

        DataPipelineExecutionResponse collectionResponse = executionService.find(
                collection.getExecutionId()
        );
        if (collectionResponse.status() != DataPipelineExecutionStatus.COMPLETED) {
            deferCollection(schedule, scheduledAt, collectionResponse.status());
            return;
        }
        if (hasRefinement(collection.getExecutionId(), schedule)) {
            clearDeferred(schedule);
            return;
        }
        startRefinement(schedule, scheduledAt, collectionResponse);
    }

    private DataPipelineExecution findPendingCollection(
            DataPipelineSchedule schedule,
            Instant scheduledAt
    ) {
        return executionRepository.findCompletedWithoutRefinementBefore(
                        schedule.collectionType(),
                        schedule.refinementType(),
                        DataPipelineExecutionStatus.COMPLETED,
                        scheduledAt,
                        PageRequest.of(0, 1)
                ).stream()
                .findFirst()
                .orElse(null);
    }

    private void startCollection(DataPipelineSchedule schedule, Instant scheduledAt) {
        DataPipelineExecutionTrigger trigger = collectionTrigger(schedule, scheduledAt);
        try {
            DataPipelineExecutionResponse response = executionService.start(
                    schedule.collectionType(),
                    trigger,
                    scheduledAt,
                    null
            );
            clearDeferred(schedule);
            meterRegistry.counter(
                    "ingest.scheduler.started",
                    "schedule", schedule.name(),
                    "stage", DataPipelineScheduleStage.COLLECTION.metricValue(),
                    "trigger", trigger.name()
            ).increment();
            log.info(
                    "event=ingest.schedule.started schedule={} stage={} trigger={} "
                            + "scheduledAt={} executionId={}",
                    schedule,
                    DataPipelineScheduleStage.COLLECTION.metricValue(),
                    trigger,
                    scheduledAt,
                    response.executionId()
            );
        }
        catch (IngestAlreadyRunningException exception) {
            deferUntilNextPoll(
                    schedule,
                    DataPipelineScheduleStage.COLLECTION,
                    scheduledAt,
                    DataPipelineScheduleDeferralReason.EXECUTION_IN_PROGRESS,
                    null
            );
        }
    }

    private void startRefinement(
            DataPipelineSchedule schedule,
            Instant scheduledAt,
            DataPipelineExecutionResponse collection
    ) {
        try {
            DataPipelineExecutionResponse response = executionService.start(
                    schedule.refinementType(),
                    collection.trigger(),
                    scheduledAt,
                    collection.executionId()
            );
            clearDeferred(schedule);
            meterRegistry.counter(
                    "ingest.scheduler.started",
                    "schedule", schedule.name(),
                    "stage", DataPipelineScheduleStage.REFINEMENT.metricValue(),
                    "trigger", collection.trigger().name()
            ).increment();
            log.info(
                    "event=ingest.schedule.started schedule={} stage={} trigger={} "
                            + "scheduledAt={} executionId={} upstreamExecutionId={}",
                    schedule,
                    DataPipelineScheduleStage.REFINEMENT.metricValue(),
                    collection.trigger(),
                    scheduledAt,
                    response.executionId(),
                    collection.executionId()
            );
        }
        catch (IngestAlreadyRunningException exception) {
            deferUntilNextPoll(
                    schedule,
                    DataPipelineScheduleStage.REFINEMENT,
                    scheduledAt,
                    DataPipelineScheduleDeferralReason.EXECUTION_IN_PROGRESS,
                    null
            );
        }
    }

    private boolean hasRefinement(UUID executionId, DataPipelineSchedule schedule) {
        return executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        schedule.refinementType(),
                        executionId
                )
                .isPresent();
    }

    private DataPipelineExecutionTrigger collectionTrigger(
            DataPipelineSchedule schedule,
            Instant scheduledAt
    ) {
        var previous = executionRepository
                .findFirstByTypeAndScheduledAtBeforeOrderByScheduledAtDesc(
                        schedule.collectionType(),
                        scheduledAt
                );
        if (previous.isEmpty()) {
            return triggerWithoutScheduledHistory(schedule, scheduledAt);
        }
        if (previous.get().getScheduledAt().equals(slotPolicy.previousSlot(schedule, scheduledAt))) {
            return DataPipelineExecutionTrigger.SCHEDULED;
        }
        return DataPipelineExecutionTrigger.RECOVERY;
    }

    private DataPipelineExecutionTrigger triggerWithoutScheduledHistory(
            DataPipelineSchedule schedule,
            Instant scheduledAt
    ) {
        if (executionRepository.existsByTypeAndStartedAtBefore(
                schedule.collectionType(),
                scheduledAt
        )) {
            return DataPipelineExecutionTrigger.RECOVERY;
        }
        return DataPipelineExecutionTrigger.SCHEDULED;
    }

    private void deferCollection(
            DataPipelineSchedule schedule,
            Instant scheduledAt,
            DataPipelineExecutionStatus status
    ) {
        if (status == DataPipelineExecutionStatus.RUNNING) {
            deferUntilNextPoll(
                    schedule,
                    DataPipelineScheduleStage.COLLECTION,
                    scheduledAt,
                    DataPipelineScheduleDeferralReason.COLLECTION_NOT_COMPLETED,
                    status.name()
            );
            return;
        }
        defer(
                schedule,
                DataPipelineScheduleStage.COLLECTION,
                scheduledAt,
                DataPipelineScheduleDeferralReason.COLLECTION_NOT_COMPLETED,
                status.name(),
                null
        );
    }

    private void deferUntilNextPoll(
            DataPipelineSchedule schedule,
            DataPipelineScheduleStage stage,
            Instant scheduledAt,
            DataPipelineScheduleDeferralReason reason,
            String detail
    ) {
        Instant observedAt = clock.instant();
        defer(
                schedule,
                stage,
                scheduledAt,
                reason,
                detail,
                observedAt,
                observedAt.plusMillis(schedulerProperties.pollIntervalMillis())
        );
    }

    private void defer(
            DataPipelineSchedule schedule,
            DataPipelineScheduleStage stage,
            Instant scheduledAt,
            DataPipelineScheduleDeferralReason reason,
            String detail,
            Instant nextRetryAt
    ) {
        Instant observedAt = clock.instant();
        defer(schedule, stage, scheduledAt, reason, detail, observedAt, nextRetryAt);
    }

    private void defer(
            DataPipelineSchedule schedule,
            DataPipelineScheduleStage stage,
            Instant scheduledAt,
            DataPipelineScheduleDeferralReason reason,
            String detail,
            Instant observedAt,
            Instant nextRetryAt
    ) {
        deferralService.defer(
                schedule,
                stage,
                scheduledAt,
                reason,
                detail,
                observedAt,
                nextRetryAt
        );
        String observation = stage.name() + ":" + scheduledAt + ":" + reason.name() + ":" + detail;
        if (observation.equals(deferredObservations.put(schedule, observation))) {
            return;
        }
        meterRegistry.counter(
                "ingest.scheduler.deferred",
                "schedule", schedule.name(),
                "stage", stage.metricValue(),
                "reason", reason.metricValue()
        ).increment();
        log.warn(
                "event=ingest.schedule.deferred schedule={} stage={} scheduledAt={} "
                        + "reason={} detail={}",
                schedule,
                stage.metricValue(),
                scheduledAt,
                reason.metricValue(),
                detail
        );
    }

    private void clearDeferred(DataPipelineSchedule schedule) {
        deferralService.resolve(schedule, clock.instant());
        deferredObservations.remove(schedule);
    }
}
