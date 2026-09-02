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
        type.steps().forEach(step -> runStep(step, progressListener));
    }

    private void runStep(DataPipelineStep step, DataPipelineProgressListener progressListener) {
        progressListener.started(step);
        Object report = execute(step);
        rejectPartialFailure(step, report);
        progressListener.completed(step);
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
