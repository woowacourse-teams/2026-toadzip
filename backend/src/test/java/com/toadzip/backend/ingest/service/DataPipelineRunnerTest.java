package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPipelineRunnerTest {

    @Mock
    private MyHomeComplexCollectionService myHomeComplexCollectionService;

    @Mock
    private LhLeaseCatalogCollectionService lhLeaseCatalogCollectionService;

    @Mock
    private MyHomeAnnouncementCollectionService myHomeAnnouncementCollectionService;

    @Mock
    private LhAnnouncementSupplyCollectionService lhAnnouncementSupplyCollectionService;

    @Mock
    private LhAnnouncementDetailCollectionService lhAnnouncementDetailCollectionService;

    @Mock
    private MyHomeComplexMappingService myHomeComplexMappingService;

    @Mock
    private LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;

    @Mock
    private MyHomeAnnouncementMappingService myHomeAnnouncementMappingService;

    @Mock
    private LhAnnouncementEnrichmentService announcementEnrichmentService;

    @Mock
    private DataPipelineProgressListener progressListener;

    private DataPipelineRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DataPipelineRunner(
                myHomeComplexCollectionService,
                lhLeaseCatalogCollectionService,
                myHomeAnnouncementCollectionService,
                lhAnnouncementSupplyCollectionService,
                lhAnnouncementDetailCollectionService,
                myHomeComplexMappingService,
                householdEnrichmentService,
                myHomeAnnouncementMappingService,
                announcementEnrichmentService
        );
    }

    @Test
    void 단지_수집_단계를_의존_순서대로_실행한다() {
        givenSuccessfulComplexCollectionReports();

        runner.run(DataPipelineType.COMPLEX_COLLECTION, progressListener);

        InOrder order = inOrder(
                myHomeComplexCollectionService,
                lhLeaseCatalogCollectionService
        );
        order.verify(myHomeComplexCollectionService).collect(any());
        order.verify(lhLeaseCatalogCollectionService).collect(any());
        verify(myHomeAnnouncementCollectionService, never()).collect(any());
    }

    @Test
    void 공고_수집_단계를_의존_순서대로_실행한다() {
        givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener);

        InOrder order = inOrder(
                myHomeAnnouncementCollectionService,
                lhAnnouncementSupplyCollectionService,
                lhAnnouncementDetailCollectionService
        );
        order.verify(myHomeAnnouncementCollectionService).collect(any());
        order.verify(lhAnnouncementSupplyCollectionService).collect();
        order.verify(lhAnnouncementDetailCollectionService).collect();
        verify(myHomeComplexCollectionService, never()).collect(any());
    }

    @Test
    void 단지_정제_단계를_의존_순서대로_실행한다() {
        givenSuccessfulComplexRefinementReports();

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener);

        InOrder order = inOrder(
                myHomeComplexMappingService,
                householdEnrichmentService
        );
        order.verify(myHomeComplexMappingService).mapAll();
        order.verify(householdEnrichmentService).enrichAll();
        verify(myHomeAnnouncementMappingService, never()).mapAll();
    }

    @Test
    void 공고_정제_단계를_의존_순서대로_실행한다() {
        givenSuccessfulAnnouncementRefinementReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, progressListener);

        InOrder order = inOrder(
                myHomeAnnouncementMappingService,
                announcementEnrichmentService
        );
        order.verify(myHomeAnnouncementMappingService).mapAll();
        order.verify(announcementEnrichmentService).enrichAll();
        verify(myHomeComplexMappingService, never()).mapAll();
    }

    @Test
    void 부분_실패_응답이면_이후_단계를_실행하지_않는다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(0));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 0, 0, 0, 1, 0));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);

    }

    @Test
    void 수집_요청_실패_건수가_있으면_다음_수집을_실행하지_않는다() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenReturn(new MyHomeComplexCollectionReport("myhome-complex", 10, 1, 20));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_COMPLEXES);

        verify(lhLeaseCatalogCollectionService, never()).collect(any());
    }

    @Test
    void 원천_행_실패_건수가_있으면_다음_정제를_실행하지_않는다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(2));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);

        verify(householdEnrichmentService, never()).enrichAll();
    }

    @Test
    void 공고_보강_원천_실패_건수를_전체_성공으로_처리하지_않는다() {
        when(myHomeAnnouncementMappingService.mapAll())
                .thenReturn(new MyHomeAnnouncementMappingReport(1, 0, 0, 1, 0, 0, 0, 0));
        when(announcementEnrichmentService.enrichAll())
                .thenReturn(LhAnnouncementEnrichmentReport.failed());

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
    }

    @Test
    void 호출_제한으로만_실패한_수집_단계는_건너뛰고_다음_단계를_실행한다() {
        ExternalDataCollectionReport rateLimited = new ExternalDataCollectionReport(
                "myhome-announcement",
                0,
                1,
                3,
                0,
                1
        );
        when(myHomeAnnouncementCollectionService.collect(any())).thenReturn(rateLimited);
        when(lhAnnouncementSupplyCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(lhAnnouncementDetailCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-detail"));

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener);

        verify(progressListener).skipped(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                rateLimited
        );
        verify(lhAnnouncementSupplyCollectionService).collect();
        verify(lhAnnouncementDetailCollectionService).collect();
    }

    @Test
    void 호출_제한과_다른_실패가_섞이면_이후_단계를_실행하지_않는다() {
        when(myHomeAnnouncementCollectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport(
                        "myhome-announcement",
                        0,
                        2,
                        4,
                        0,
                        1
                ));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class);

        verify(lhAnnouncementSupplyCollectionService, never()).collect();
    }

    @Test
    void 주소_API_호출_제한으로만_실패한_단지_정제는_건너뛴다() {
        MyHomeComplexMappingReport rateLimited =
                MyHomeComplexMappingReport.rateLimitedRows(2);
        when(myHomeComplexMappingService.mapAll()).thenReturn(rateLimited);
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 1, 1, 0, 0, 0));

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener);

        verify(progressListener).skipped(
                DataPipelineStep.MAP_MYHOME_COMPLEXES,
                "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                rateLimited
        );
        verify(householdEnrichmentService).enrichAll();
    }

    private void givenSuccessfulComplexCollectionReports() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenReturn(new MyHomeComplexCollectionReport("myhome-complex", 1, 0, 1));
        when(lhLeaseCatalogCollectionService.collect(any()))
                .thenReturn(collectionReport("lh-lease-catalog"));
    }

    private void givenSuccessfulAnnouncementCollectionReports() {
        when(myHomeAnnouncementCollectionService.collect(any()))
                .thenReturn(collectionReport("myhome-announcement"));
        when(lhAnnouncementSupplyCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(lhAnnouncementDetailCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-detail"));
    }

    private void givenSuccessfulComplexRefinementReports() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(0));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 1, 1, 0, 0, 0));
    }

    private void givenSuccessfulAnnouncementRefinementReports() {
        when(myHomeAnnouncementMappingService.mapAll())
                .thenReturn(new MyHomeAnnouncementMappingReport(1, 0, 0, 1, 0, 0, 0, 0));
        when(announcementEnrichmentService.enrichAll())
                .thenReturn(LhAnnouncementEnrichmentReport.empty());
    }

    private ExternalDataCollectionReport collectionReport(String operation) {
        return new ExternalDataCollectionReport(operation, 1, 0, 1);
    }

    private MyHomeComplexMappingReport complexMappingReport(int failedCount) {
        return new MyHomeComplexMappingReport(1, 0, 0, 1, 0, 0, 0, failedCount);
    }
}
