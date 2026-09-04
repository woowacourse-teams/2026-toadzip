package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import org.springframework.stereotype.Service;

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

    public DataPipelineRunner(
            MyHomeComplexCollectionService myHomeComplexCollectionService,
            LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService,
            MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService,
            LhAnnouncementSupplyCollectionService lhAnnouncementSupplyCollectionService,
            LhAnnouncementDetailCollectionService lhAnnouncementDetailCollectionService,
            MyHomeComplexMappingService myHomeComplexMappingService,
            LhHousingTypeHouseholdEnrichmentService householdEnrichmentService,
            MyHomeAnnouncementMappingService myHomeAnnouncementMappingService,
            LhAnnouncementEnrichmentService announcementEnrichmentService
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
    }

    public void run(DataPipelineType type, DataPipelineProgressListener progressListener) {
        DataPipelinePartialFailureException firstPartialFailure = null;
        for (DataPipelineStep step : type.steps()) {
            try {
                runStep(step, progressListener);
            }
            catch (DataPipelinePartialFailureException exception) {
                if (firstPartialFailure == null) {
                    firstPartialFailure = exception;
                }
            }
        }
        if (firstPartialFailure != null) {
            throw firstPartialFailure;
        }
    }

    private void runStep(DataPipelineStep step, DataPipelineProgressListener progressListener) {
        progressListener.started(step);
        Object report = execute(step);
        if (hasOnlyRateLimitedFailures(report)) {
            progressListener.skipped(step, RATE_LIMIT_SKIP_REASON, report);
            return;
        }
        rejectPartialFailure(step, report);
        progressListener.completed(step);
    }

    private boolean hasOnlyRateLimitedFailures(Object report) {
        return switch (report) {
            case MyHomeComplexCollectionReport result -> result.failedRequestCount() > 0
                    && result.failedRequestCount() == result.rateLimitedRequestCount();
            case ExternalDataCollectionReport result -> result.failedRequestCount() > 0
                    && result.failedRequestCount() == result.rateLimitedRequestCount();
            case MyHomeComplexMappingReport result -> result.failedSourceRowCount() > 0
                    && result.failedSourceRowCount() == result.rateLimitedSourceRowCount();
            default -> false;
        };
    }

    private Object execute(DataPipelineStep step) {
        return switch (step) {
            case COLLECT_MYHOME_COMPLEXES -> myHomeComplexCollectionService.collect(
                    MyHomeComplexCollectionRequest.allRegions(500, 1_000)
            );
            case COLLECT_LH_LEASE_CATALOG -> lhLeaseCatalogCollectionService.collect(
                    new LhLeaseCatalogCollectionRequest(9_999, 1)
            );
            case COLLECT_MYHOME_ANNOUNCEMENTS -> myHomeAnnouncementCollectionService.collect(
                    new MyHomeAnnouncementCollectionRequest(10, 1_000)
            );
            case COLLECT_LH_ANNOUNCEMENT_SUPPLIES -> lhAnnouncementSupplyCollectionService.collect();
            case COLLECT_LH_ANNOUNCEMENT_DETAILS -> lhAnnouncementDetailCollectionService.collect();
            case MAP_MYHOME_COMPLEXES -> myHomeComplexMappingService.mapAll();
            case ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS -> householdEnrichmentService.enrichAll();
            case MAP_MYHOME_ANNOUNCEMENTS -> myHomeAnnouncementMappingService.mapAll();
            case ENRICH_LH_ANNOUNCEMENTS -> announcementEnrichmentService.enrichAll();
        };
    }

    private void rejectPartialFailure(DataPipelineStep step, Object report) {
        boolean failed = switch (report) {
            case MyHomeComplexCollectionReport result -> result.failedRequestCount() > 0;
            case ExternalDataCollectionReport result -> result.failedRequestCount() > 0;
            case MyHomeComplexMappingReport result -> result.failedSourceRowCount() > 0;
            case LhHousingTypeHouseholdEnrichmentReport result -> result.failedSourceComplexCount() > 0;
            case MyHomeAnnouncementMappingReport result -> result.failedSourceRowCount() > 0;
            case LhAnnouncementEnrichmentReport result -> result.failedSourceCount() > 0;
            default -> throw new IllegalStateException("지원하지 않는 데이터 수집·정제 결과입니다.");
        };
        if (failed) {
            throw new DataPipelinePartialFailureException(step, report);
        }
    }
}
