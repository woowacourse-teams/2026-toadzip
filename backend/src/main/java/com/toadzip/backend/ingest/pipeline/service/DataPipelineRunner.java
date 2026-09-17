package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
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
        for (DataPipelineStep step : type.steps()) {
            try {
                runStep(step, progressListener);
            }
            catch (DataPipelinePartialFailureException exception) {
                if (firstReportedPartialFailure == null) {
                    firstReportedPartialFailure = exception;
                }
            }
        }
        if (firstReportedPartialFailure != null) {
            throw firstReportedPartialFailure;
        }
    }

    private void runStep(DataPipelineStep step, DataPipelineProgressListener progressListener) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failed";
        try {
            progressListener.started(step);
            DataPipelineStepResult result = execute(step);
            if (result.failedOnlyByRateLimit()) {
                progressListener.skipped(step, RATE_LIMIT_SKIP_REASON, result.serverResponse());
                outcome = "rate_limited";
                return;
            }
            rejectPartialFailure(step, result);
            progressListener.completed(step);
            outcome = "completed";
        }
        finally {
            long durationNanos = sample.stop(meterRegistry.timer(
                    "ingest.pipeline.step", "step", step.name(), "result", outcome
            ));
            log.info("event=ingest.pipeline.step.finished executionId={} step={} result={} durationMs={}",
                    MDC.get("traceId"), step, outcome, durationNanos / 1_000_000);
        }
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
