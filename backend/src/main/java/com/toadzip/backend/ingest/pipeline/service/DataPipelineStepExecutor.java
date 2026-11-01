package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.api.LhLeaseCatalogCollectionCommand;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionCommand;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionCommand;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.collection.service.ShAnnouncementCollectionService;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.lhrefinement.service.LhAnnouncementRefinementService;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 단계마다 실행할 수집·정제 서비스와 그 입력을 정하고, 결과를 단계 결과 계약으로 바꾼다.
 * 단건 등록은 대상 공고만 다루는 등록 서비스로 보낸다.
 */
@Component
@RequiredArgsConstructor
class DataPipelineStepExecutor {

    private final MyHomeComplexCollectionService myHomeComplexCollectionService;
    private final LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService;
    private final MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService;
    private final LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;
    private final LhAnnouncementExternalCollectionService collectionService;
    private final MyHomeComplexMappingService myHomeComplexMappingService;
    private final LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;
    private final MyHomeAnnouncementMappingService myHomeAnnouncementMappingService;
    private final LhAnnouncementEnrichmentService announcementEnrichmentService;
    private final LhAnnouncementRefinementService lhAnnouncementRefinementService;
    private final DataPipelineStepResultAdapter resultAdapter;
    private final AnnouncementRegistrationService registrationService;
    private final ShAnnouncementCollectionService shAnnouncementCollectionService;

    DataPipelineStepResult execute(DataPipelineStep step, String targetIdentifier) {
        if (targetIdentifier != null) {
            return registrationService.execute(step, targetIdentifier);
        }
        return switch (step) {
            case COLLECT_SH_ANNOUNCEMENTS -> resultAdapter.adapt(shAnnouncementCollectionService.collect());
            case COLLECT_MYHOME_COMPLEXES -> resultAdapter.adapt(myHomeComplexCollectionService.collect(
                    MyHomeComplexCollectionCommand.allRegions(500, 1_000)
            ));
            case COLLECT_LH_LEASE_CATALOG -> resultAdapter.adapt(lhLeaseCatalogCollectionService.collect(
                    new LhLeaseCatalogCollectionCommand(9_999, 1)
            ));
            case COLLECT_MYHOME_ANNOUNCEMENTS -> resultAdapter.adapt(myHomeAnnouncementCollectionService.collect(
                    new MyHomeAnnouncementCollectionCommand(500, 1_000)
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
            case MAP_LH_ANNOUNCEMENTS -> resultAdapter.adapt(lhAnnouncementRefinementService.refineAll());
        };
    }
}
