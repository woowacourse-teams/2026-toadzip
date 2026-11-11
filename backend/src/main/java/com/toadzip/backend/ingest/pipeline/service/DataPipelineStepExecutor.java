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
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationExecutionService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.dto.BoardCollectionRefinementReport;
import com.toadzip.backend.ingest.pipeline.dto.LhCollectionRefinementReport;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.sh.collection.service.ShAnnouncementCollectionService;
import com.toadzip.backend.ingest.sh.refinement.service.ShAnnouncementRefinementService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 단계마다 실행할 수집·정제 서비스와 그 입력을 정하고, 결과를 단계 결과 계약으로 바꾼다.
 * 지원을 종료한 마이홈 공고 수집·정제, LH 보강, SH·GH 수집·정제 분리 단계는 지난 실행 이력으로만 남아 실행하지 않는다.
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
    private final DataPipelineExecutionRepository executions;
    private final MyHomeInitialRegistrationExecutionService initialRegistration;

    DataPipelineStepResult registerComplexes(String actor) {
        return resultAdapter.adapt(initialRegistration.register(actor));
    }

    DataPipelineStepResult retryComplexes(String actor, List<String> identifiers) {
        return resultAdapter.adapt(initialRegistration.retry(actor, identifiers));
    }

    DataPipelineStepResult execute(DataPipelineStep step) {
        return switch (step) {
            case REGISTER_MYHOME_COMPLEXES -> throw new IllegalStateException("신규 단지 등록에는 실행자가 필요합니다.");
            case COLLECT_REFINE_SH_ANNOUNCEMENTS -> collectAndRefineSh();
            case COLLECT_REFINE_GH_ANNOUNCEMENTS -> collectAndRefineGh();
            case COLLECT_REFINE_LH_ANNOUNCEMENTS -> collectAndRefineLh();
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
            case COLLECT_MYHOME_ANNOUNCEMENTS, MAP_MYHOME_ANNOUNCEMENTS, ENRICH_LH_ANNOUNCEMENTS,
                 COLLECT_SH_ANNOUNCEMENTS, MAP_SH_ANNOUNCEMENTS, COLLECT_GH_ANNOUNCEMENTS, MAP_GH_ANNOUNCEMENTS -> throw new IllegalStateException(
                    "지원을 종료한 단계는 실행하지 않습니다: " + step
            );
            case MAP_LH_ANNOUNCEMENTS -> resultAdapter.adapt(lhAnnouncementRefinementService.refineAll());
        };
    }

    private DataPipelineStepResult collectAndRefineLh() {
        // 수집이 요청 묶음을 마칠 때마다 그 공고를 정제한다. 외부 호출만 병렬이고 정제는 이 스레드에서 차례로 한다.
        var refinement = lhAnnouncementRefinementService.begin();
        var collection = collectionService.collectBundles(refinement::refineBundle);
        refinement.finish(executionStartedAt(), collection.selectedPanIds());
        return resultAdapter.adapt(new LhCollectionRefinementReport(
                collection.supply(), collection.detail(), refinement.report()));
    }

    /** 이번 실행이 시작된 시각. 실행 밖에서 부르면 이번 실행에서 바뀐 공고를 정하지 못해 마무리 정제를 건너뛴다. */
    private Instant executionStartedAt() {
        return IngestExecutionScope.currentExecutionId().flatMap(executions::findByExecutionId)
                .map(DataPipelineExecution::getStartedAt).orElse(Instant.MAX);
    }

    private DataPipelineStepResult collectAndRefineSh() {
        var refinement = shAnnouncementRefinementService.begin();
        var collection = shAnnouncementCollectionService.collect(refinement::refinePage);
        refinement.finish();
        return resultAdapter.adapt(new BoardCollectionRefinementReport(collection, refinement.report()));
    }

    private DataPipelineStepResult collectAndRefineGh() {
        var refinement = ghAnnouncementRefinementService.begin();
        var collection = ghAnnouncementCollectionService.collect(refinement::refinePage);
        refinement.finish();
        return resultAdapter.adapt(new BoardCollectionRefinementReport(collection, refinement.report()));
    }
}
