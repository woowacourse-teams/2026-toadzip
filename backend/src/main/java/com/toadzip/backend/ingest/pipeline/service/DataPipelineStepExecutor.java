package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionCommand;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.gh.collection.service.GhAnnouncementCollectionService;
import com.toadzip.backend.ingest.gh.refinement.service.GhAnnouncementRefinementService;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.api.LhLeaseCatalogCollectionCommand;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementRefinementService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.sh.collection.service.ShAnnouncementCollectionService;
import com.toadzip.backend.ingest.sh.refinement.service.ShAnnouncementRefinementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 단계마다 실행할 수집·정제 서비스와 그 입력을 정하고, 결과를 단계 결과 계약으로 바꾼다.
 * 지원을 종료한 마이홈 공고 수집·정제와 LH 보강 단계는 지난 실행 이력으로만 남아 실행하지 않는다.
 */
@Component
@RequiredArgsConstructor
class DataPipelineStepExecutor {

    private final MyHomeComplexCollectionService myHomeComplexCollectionService;
    private final LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService;
    private final LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;
    private final LhAnnouncementExternalCollectionService collectionService;
    private final MyHomeComplexMappingService myHomeComplexMappingService;
    private final LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;
    private final LhAnnouncementRefinementService lhAnnouncementRefinementService;
    private final DataPipelineStepResultAdapter resultAdapter;
    private final ShAnnouncementCollectionService shAnnouncementCollectionService;
    private final GhAnnouncementCollectionService ghAnnouncementCollectionService;
    private final ShAnnouncementRefinementService shAnnouncementRefinementService;
    private final GhAnnouncementRefinementService ghAnnouncementRefinementService;

    DataPipelineStepResult execute(DataPipelineStep step) {
        return switch (step) {
            case MAP_GH_ANNOUNCEMENTS -> resultAdapter.adapt(ghAnnouncementRefinementService.refineAll());
            case MAP_SH_ANNOUNCEMENTS -> resultAdapter.adapt(shAnnouncementRefinementService.refineAll());
            case COLLECT_SH_ANNOUNCEMENTS -> resultAdapter.adapt(shAnnouncementCollectionService.collect());
            case COLLECT_GH_ANNOUNCEMENTS -> resultAdapter.adapt(ghAnnouncementCollectionService.collect());
            case COLLECT_MYHOME_COMPLEXES -> resultAdapter.adapt(myHomeComplexCollectionService.collect(
                    MyHomeComplexCollectionCommand.allRegions(500, 1_000)
            ));
            case COLLECT_LH_LEASE_CATALOG -> resultAdapter.adapt(lhLeaseCatalogCollectionService.collect(
                    new LhLeaseCatalogCollectionCommand(9_999, 1)
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
            case COLLECT_MYHOME_ANNOUNCEMENTS, MAP_MYHOME_ANNOUNCEMENTS, ENRICH_LH_ANNOUNCEMENTS -> throw new IllegalStateException(
                    "지원을 종료한 단계는 실행하지 않습니다: " + step
            );
            case MAP_LH_ANNOUNCEMENTS -> resultAdapter.adapt(lhAnnouncementRefinementService.refineAll());
        };
    }
}
