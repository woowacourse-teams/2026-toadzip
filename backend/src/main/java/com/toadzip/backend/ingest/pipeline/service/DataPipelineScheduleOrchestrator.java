package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
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

    private static final String COLLECTION_STAGE = "collection";
    private static final String REFINEMENT_STAGE = "refinement";
    private static final String EXECUTION_IN_PROGRESS = "execution_in_progress";
    private static final String COLLECTION_NOT_COMPLETED = "collection_not_completed";

    private final DataPipelineExecutionService executionService;
    private final DataPipelineExecutionRepository executionRepository;
    private final DataPipelineScheduleSlotPolicy slotPolicy;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<DataPipelineSchedule, String> deferredObservations =
            new EnumMap<>(DataPipelineSchedule.class);

    public DataPipelineScheduleOrchestrator(
            DataPipelineExecutionService executionService,
            DataPipelineExecutionRepository executionRepository,
            DataPipelineScheduleSlotPolicy slotPolicy,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.executionService = executionService;
        this.executionRepository = executionRepository;
        this.slotPolicy = slotPolicy;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ingest.scheduler.poll-interval-millis:60000}")
    public void runScheduledCycle() {
        runOnce(clock.instant());
    }

    void runOnce(Instant now) {
        for (DataPipelineSchedule schedule : DataPipelineSchedule.values()) {
            try {
                process(schedule, now);
            }
            catch (RuntimeException exception) {
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
            defer(
                    schedule,
                    COLLECTION_STAGE,
                    scheduledAt,
                    COLLECTION_NOT_COMPLETED,
                    collectionResponse.status().name()
            );
            return;
        }
        if (hasRefinement(collection.getExecutionId(), schedule)) {
            clearDeferred(schedule);
            return;
        }
        startRefinement(schedule, scheduledAt, collectionResponse);
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
                    "stage", COLLECTION_STAGE,
                    "trigger", trigger.name()
            ).increment();
            log.info(
                    "event=ingest.schedule.started schedule={} stage={} trigger={} "
                            + "scheduledAt={} executionId={}",
                    schedule,
                    COLLECTION_STAGE,
                    trigger,
                    scheduledAt,
                    response.executionId()
            );
        }
        catch (IngestAlreadyRunningException exception) {
            defer(
                    schedule,
                    COLLECTION_STAGE,
                    scheduledAt,
                    EXECUTION_IN_PROGRESS,
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
                    "stage", REFINEMENT_STAGE,
                    "trigger", collection.trigger().name()
            ).increment();
            log.info(
                    "event=ingest.schedule.started schedule={} stage={} trigger={} "
                            + "scheduledAt={} executionId={} upstreamExecutionId={}",
                    schedule,
                    REFINEMENT_STAGE,
                    collection.trigger(),
                    scheduledAt,
                    response.executionId(),
                    collection.executionId()
            );
        }
        catch (IngestAlreadyRunningException exception) {
            defer(
                    schedule,
                    REFINEMENT_STAGE,
                    scheduledAt,
                    EXECUTION_IN_PROGRESS,
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
            return DataPipelineExecutionTrigger.SCHEDULED;
        }
        if (previous.get().getScheduledAt().equals(slotPolicy.previousSlot(schedule, scheduledAt))) {
            return DataPipelineExecutionTrigger.SCHEDULED;
        }
        return DataPipelineExecutionTrigger.RECOVERY;
    }

    private void defer(
            DataPipelineSchedule schedule,
            String stage,
            Instant scheduledAt,
            String reason,
            String detail
    ) {
        String observation = stage + ":" + scheduledAt + ":" + reason + ":" + detail;
        if (observation.equals(deferredObservations.put(schedule, observation))) {
            return;
        }
        meterRegistry.counter(
                "ingest.scheduler.deferred",
                "schedule", schedule.name(),
                "stage", stage,
                "reason", reason
        ).increment();
        log.warn(
                "event=ingest.schedule.deferred schedule={} stage={} scheduledAt={} "
                        + "reason={} detail={}",
                schedule,
                stage,
                scheduledAt,
                reason,
                detail
        );
    }

    private void clearDeferred(DataPipelineSchedule schedule) {
        deferredObservations.remove(schedule);
    }
}
