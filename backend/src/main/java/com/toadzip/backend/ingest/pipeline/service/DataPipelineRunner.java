package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * {@link DataPipelineType#steps()}에 정의된 순서로 수집·정제 서비스를 실행한다.
 * 데이터 처리 경로는 {@link #execute(DataPipelineStep)}에서 시작한다.
 * 실행 잠금·중단·복구는 {@link DataPipelineExecutionService}가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPipelineRunner {

    private static final String RATE_LIMIT_SKIP_REASON =
            "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.";

    private final MyHomeComplexCollectionService myHomeComplexCollectionService;
    private final LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService;
    private final MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService;
    private final LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;
    private final LhAnnouncementExternalCollectionService collectionService;
    private final MyHomeComplexMappingService myHomeComplexMappingService;
    private final LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;
    private final MyHomeAnnouncementMappingService myHomeAnnouncementMappingService;
    private final LhAnnouncementEnrichmentService announcementEnrichmentService;
    private final DataPipelineStepResultAdapter resultAdapter;
    private final DataPipelineExecutionStateService executionStateService;
    private final MeterRegistry meterRegistry;

    public void run(DataPipelineType type, UUID executionId) {
        DataPipelinePartialFailureException firstReportedPartialFailure = null;
        DataPipelineStep partiallyFailedStep = null;
        boolean lhRateLimited = false;
        boolean collectionRateLimited = false;
        for (DataPipelineStep step : type.steps()) {
            if (type.requiresSuccessfulCollection(step) && firstReportedPartialFailure != null
                    && firstReportedPartialFailure.getStep().isCollection()) {
                throw firstReportedPartialFailure;
            }
            StepOutcome outcome = runStep(
                    executionId, step, partiallyFailedStep,
                    skipReason(type, step, collectionRateLimited, lhRateLimited)
            );
            lhRateLimited |= outcome.rateLimited() && isLhAnnouncementCollection(step);
            collectionRateLimited |= outcome.rateLimited() && step.isCollection();
            partiallyFailedStep = null;
            if (outcome.partialFailure() == null) {
                continue;
            }
            partiallyFailedStep = step;
            if (firstReportedPartialFailure == null) {
                firstReportedPartialFailure = new DataPipelinePartialFailureException(
                        step, outcome.partialFailure().serverResponse()
                );
            }
        }
        if (firstReportedPartialFailure != null) {
            throw firstReportedPartialFailure;
        }
    }

    private StepOutcome runStep(
            UUID executionId,
            DataPipelineStep step,
            DataPipelineStep partiallyFailedStep,
            String skipReason
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failed";
        try {
            startStep(executionId, step, partiallyFailedStep);
            if (skipReason != null) {
                executionStateService.skipStep(
                        executionId, step, skipReason, "{}"
                );
                outcome = "skipped";
                if (isLhAnnouncementCollection(step)) {
                    outcome = "rate_limited";
                }
                return new StepOutcome(false, null);
            }
            DataPipelineStepResult result = execute(step);
            if (result.failedOnlyByRateLimit()) {
                executionStateService.skipStep(executionId, step, RATE_LIMIT_SKIP_REASON, result.serverResponse());
                outcome = "rate_limited";
                return new StepOutcome(true, null);
            }
            if (result.failed()) {
                executionStateService.recordPartialFailure(executionId, step, result.serverResponse());
                return new StepOutcome(
                        result.rateLimitedFailureCount() > 0, result
                );
            }
            if (result.hasWarnings()) {
                executionStateService.completeStepWithWarnings(executionId, step, result.serverResponse());
                outcome = "completed_with_warnings";
                return new StepOutcome(false, null);
            }
            executionStateService.completeStep(executionId, step, result.serverResponse());
            outcome = "completed";
            return new StepOutcome(false, null);
        }
        finally {
            long durationNanos = sample.stop(meterRegistry.timer(
                    "ingest.pipeline.step", "step", step.name(), "result", outcome
            ));
            log.info("event=ingest.pipeline.step.finished executionId={} step={} result={} durationMs={}",
                    MDC.get("executionId"), step, outcome, durationNanos / 1_000_000);
        }
    }

    private String skipReason(
            DataPipelineType type, DataPipelineStep step, boolean collectionRateLimited, boolean lhRateLimited
    ) {
        if (type.requiresSuccessfulCollection(step) && collectionRateLimited) {
            return "수집이 호출 제한으로 완료되지 않아 자동 정제를 실행하지 않았습니다. "
                    + "수집 결과를 확인한 뒤 저장된 원천으로 정제를 실행할 수 있습니다.";
        }
        if (lhRateLimited && isLhAnnouncementCollection(step)) {
            return "앞선 LH 공고 단계의 호출 제한으로 이 단계를 건너뛰었습니다.";
        }
        return null;
    }

    private void startStep(UUID executionId, DataPipelineStep step, DataPipelineStep partiallyFailedStep) {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
        if (partiallyFailedStep != null) {
            executionStateService.startStepAfterPartialFailure(executionId, partiallyFailedStep, step);
            return;
        }
        executionStateService.startStep(executionId, step);
    }

    private boolean isLhAnnouncementCollection(DataPipelineStep step) {
        return step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG
                || step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
                || step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS;
    }

    private DataPipelineStepResult execute(DataPipelineStep step) {
        return switch (step) {
            case COLLECT_MYHOME_COMPLEXES -> resultAdapter.adapt(myHomeComplexCollectionService.collect(
                    MyHomeComplexCollectionRequest.allRegions(500, 1_000)
            ));
            case COLLECT_LH_LEASE_CATALOG -> resultAdapter.adapt(lhLeaseCatalogCollectionService.collect(
                    new LhLeaseCatalogCollectionRequest(9_999, 1)
            ));
            case COLLECT_MYHOME_ANNOUNCEMENTS -> resultAdapter.adapt(myHomeAnnouncementCollectionService.collect(
                    new MyHomeAnnouncementCollectionRequest(500, 1_000)
            ));
            case COLLECT_LH_ANNOUNCEMENT_CATALOG -> resultAdapter.adapt(
                    lhAnnouncementCatalogCollectionService.collect()
            );
            case COLLECT_LH_ANNOUNCEMENT_SUPPLIES -> resultAdapter.adapt(
                    collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)
            );
            case COLLECT_LH_ANNOUNCEMENT_DETAILS -> resultAdapter.adapt(
                    collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)
            );
            case MAP_MYHOME_COMPLEXES -> resultAdapter.adapt(myHomeComplexMappingService.mapAll());
            case ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS -> resultAdapter.adapt(
                    householdEnrichmentService.enrichAll()
            );
            case MAP_MYHOME_ANNOUNCEMENTS -> resultAdapter.adapt(
                    myHomeAnnouncementMappingService.mapAll()
            );
            case ENRICH_LH_ANNOUNCEMENTS -> resultAdapter.adapt(
                    announcementEnrichmentService.enrichAll()
            );
        };
    }

    private record StepOutcome(boolean rateLimited, DataPipelineStepResult partialFailure) {
    }
}
