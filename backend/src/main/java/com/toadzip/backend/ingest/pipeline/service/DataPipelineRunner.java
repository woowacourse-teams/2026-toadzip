package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
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
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataPipelineRunner {

    private static final String RATE_LIMIT_SKIP_REASON =
            "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.";

    private final MyHomeComplexCollectionService myHomeComplexCollectionService;
    private final LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService;
    private final MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService;
    private final LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;
    private final LhAnnouncementSupplyCollectionService lhAnnouncementSupplyCollectionService;
    private final LhAnnouncementDetailCollectionService lhAnnouncementDetailCollectionService;
    private final MyHomeComplexMappingService myHomeComplexMappingService;
    private final LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;
    private final MyHomeAnnouncementMappingService myHomeAnnouncementMappingService;
    private final LhAnnouncementEnrichmentService announcementEnrichmentService;
    private final DataPipelineStepResultAdapter resultAdapter;
    private final MeterRegistry meterRegistry;

    public DataPipelineRunner(
            MyHomeComplexCollectionService myHomeComplexCollectionService,
            LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService,
            MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService,
            LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService,
            LhAnnouncementSupplyCollectionService lhAnnouncementSupplyCollectionService,
            LhAnnouncementDetailCollectionService lhAnnouncementDetailCollectionService,
            MyHomeComplexMappingService myHomeComplexMappingService,
            LhHousingTypeHouseholdEnrichmentService householdEnrichmentService,
            MyHomeAnnouncementMappingService myHomeAnnouncementMappingService,
            LhAnnouncementEnrichmentService announcementEnrichmentService,
            DataPipelineStepResultAdapter resultAdapter,
            MeterRegistry meterRegistry
    ) {
        this.myHomeComplexCollectionService = myHomeComplexCollectionService;
        this.lhLeaseCatalogCollectionService = lhLeaseCatalogCollectionService;
        this.myHomeAnnouncementCollectionService = myHomeAnnouncementCollectionService;
        this.lhAnnouncementCatalogCollectionService = lhAnnouncementCatalogCollectionService;
        this.lhAnnouncementSupplyCollectionService = lhAnnouncementSupplyCollectionService;
        this.lhAnnouncementDetailCollectionService = lhAnnouncementDetailCollectionService;
        this.myHomeComplexMappingService = myHomeComplexMappingService;
        this.householdEnrichmentService = householdEnrichmentService;
        this.myHomeAnnouncementMappingService = myHomeAnnouncementMappingService;
        this.announcementEnrichmentService = announcementEnrichmentService;
        this.resultAdapter = resultAdapter;
        this.meterRegistry = meterRegistry;
    }

    public void run(DataPipelineType type, DataPipelineProgressListener progressListener) {
        DataPipelinePartialFailureException firstReportedPartialFailure = null;
        boolean lhRateLimited = false;
        for (DataPipelineStep step : type.steps()) {
            try {
                lhRateLimited |= runStep(step, progressListener, lhRateLimited && isLhAnnouncementCollection(step));
            }
            catch (DataPipelinePartialFailureException exception) {
                progressListener.partiallyFailed(
                        exception.getStep(),
                        exception.getServerResponse()
                );
                if (firstReportedPartialFailure == null) {
                    firstReportedPartialFailure = exception;
                }
            }
        }
        if (firstReportedPartialFailure != null) {
            throw firstReportedPartialFailure;
        }
    }

    private boolean runStep(
            DataPipelineStep step, DataPipelineProgressListener progressListener, boolean blockedByLhRateLimit
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failed";
        try {
            progressListener.started(step);
            if (blockedByLhRateLimit) {
                progressListener.skipped(step, "앞선 LH 공고 단계의 호출 제한으로 이 단계를 건너뛰었습니다.", "{}");
                outcome = "rate_limited";
                return true;
            }
            DataPipelineStepResult result = execute(step);
            if (result.failedOnlyByRateLimit()) {
                progressListener.skipped(step, RATE_LIMIT_SKIP_REASON, result.serverResponse());
                outcome = "rate_limited";
                return isLhAnnouncementCollection(step);
            }
            rejectPartialFailure(step, result);
            if (result.hasWarnings()) {
                progressListener.completedWithWarnings(step, result.serverResponse());
                outcome = "completed_with_warnings";
                return false;
            }
            progressListener.completed(step, result.serverResponse());
            outcome = "completed";
            return false;
        }
        finally {
            long durationNanos = sample.stop(meterRegistry.timer(
                    "ingest.pipeline.step", "step", step.name(), "result", outcome
            ));
            log.info("event=ingest.pipeline.step.finished executionId={} step={} result={} durationMs={}",
                    MDC.get("executionId"), step, outcome, durationNanos / 1_000_000);
        }
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
            case COLLECT_LH_ANNOUNCEMENT_CATALOG -> resultAdapter.adapt(lhAnnouncementCatalogCollectionService.collect());
            case COLLECT_LH_ANNOUNCEMENT_SUPPLIES -> resultAdapter.adapt(
                    lhAnnouncementSupplyCollectionService.collect()
            );
            case COLLECT_LH_ANNOUNCEMENT_DETAILS -> resultAdapter.adapt(
                    lhAnnouncementDetailCollectionService.collect()
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

    private void rejectPartialFailure(DataPipelineStep step, DataPipelineStepResult result) {
        if (result.failed()) {
            throw new DataPipelinePartialFailureException(step, result.serverResponse());
        }
    }
}
