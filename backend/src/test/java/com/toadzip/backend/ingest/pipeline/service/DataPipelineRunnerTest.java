package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
import com.toadzip.backend.ingest.collection.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

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

    private DataPipelineStepResultAdapter resultAdapter;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        resultAdapter = new DataPipelineStepResultAdapter(JsonMapper.builder().build());
        runner = new DataPipelineRunner(
                myHomeComplexCollectionService,
                lhLeaseCatalogCollectionService,
                myHomeAnnouncementCollectionService,
                lhAnnouncementSupplyCollectionService,
                lhAnnouncementDetailCollectionService,
                myHomeComplexMappingService,
                householdEnrichmentService,
                myHomeAnnouncementMappingService,
                announcementEnrichmentService,
                resultAdapter,
                meterRegistry
        );
    }

    @Test
    void 완료된_공고_수집_단계별_시간을_기록한다() {
        givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener);

        for (DataPipelineStep step : DataPipelineType.ANNOUNCEMENT_COLLECTION.steps()) {
            assertThat(meterRegistry.find("ingest.pipeline.step")
                    .tags("step", step.name(), "result", "completed").timer()).isNotNull();
        }
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
    void 마이홈_공고는_한_페이지에_500행씩_수집한다() {
        givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener);

        verify(myHomeAnnouncementCollectionService).collect(new MyHomeAnnouncementCollectionRequest(500, 1_000));
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
    void 마지막_단계가_부분_실패면_파이프라인_실패로_처리한다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(0));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 0, 0, 0, 1, 0));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
    }

    @Test
    void 마이홈_수집이_일부_실패해도_뒤의_LH_수집을_실행한다() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenReturn(new MyHomeComplexCollectionReport("myhome-complex", 10, 1, 20));
        when(lhLeaseCatalogCollectionService.collect(any()))
                .thenReturn(collectionReport("lh-lease-catalog"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_COMPLEXES);

        verify(lhLeaseCatalogCollectionService).collect(any());
    }

    @Test
    void 마이홈_단지_정제가_일부_실패해도_LH_세대수_보강을_실행한다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(2));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 1, 1, 0, 0, 0));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_REFINEMENT, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);

        verify(householdEnrichmentService).enrichAll();
    }

    @Test
    void 마이홈_공고_정제가_일부_실패해도_LH_공고_보강을_실행한다() {
        MyHomeAnnouncementMappingReport partialFailure =
                MyHomeAnnouncementMappingReport.failedRows(2);
        when(myHomeAnnouncementMappingService.mapAll()).thenReturn(partialFailure);
        when(announcementEnrichmentService.enrichAll())
                .thenReturn(LhAnnouncementEnrichmentReport.empty());

        assertThatThrownBy(() -> runner.run(
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                progressListener
        ))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);

        verify(announcementEnrichmentService).enrichAll();
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
                resultAdapter.adapt(rateLimited).serverResponse()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tag("result", "rate_limited").timer().count()).isOne();
        verify(lhAnnouncementSupplyCollectionService).collect();
        verify(lhAnnouncementDetailCollectionService).collect();
    }

    @Test
    void 마이홈_공고_수집이_일부_실패해도_LH_증분_수집을_실행한다() {
        when(myHomeAnnouncementCollectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport(
                        "myhome-announcement",
                        0,
                        2,
                        4,
                        0,
                        1
                ));
        when(lhAnnouncementSupplyCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(lhAnnouncementDetailCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-detail"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class);

        verify(lhAnnouncementSupplyCollectionService).collect();
        verify(lhAnnouncementDetailCollectionService).collect();
    }

    @Test
    void 여러_단계가_부분_실패해도_모두_실행하고_최초_실패를_대표로_반환한다() {
        when(myHomeAnnouncementCollectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("myhome-announcement", 0, 1, 1));
        when(lhAnnouncementSupplyCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 1));
        when(lhAnnouncementDetailCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 0, 1, 1));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, progressListener))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        InOrder order = inOrder(
                myHomeAnnouncementCollectionService,
                lhAnnouncementSupplyCollectionService,
                lhAnnouncementDetailCollectionService
        );
        order.verify(myHomeAnnouncementCollectionService).collect(any());
        order.verify(lhAnnouncementSupplyCollectionService).collect();
        order.verify(lhAnnouncementDetailCollectionService).collect();
    }

    @Test
    void 예상하지_못한_예외가_발생하면_이후_단계를_실행하지_않는다() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, progressListener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(lhLeaseCatalogCollectionService, never()).collect(any());
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tags("step", "COLLECT_MYHOME_COMPLEXES", "result", "failed").timer().count()).isOne();
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
                resultAdapter.adapt(rateLimited).serverResponse()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tag("result", "rate_limited").timer().count()).isOne();
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
