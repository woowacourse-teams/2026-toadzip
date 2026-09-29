package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
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
    private LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;

    @Mock
    private LhAnnouncementExternalCollectionService collectionService;

    @Mock
    private MyHomeComplexMappingService myHomeComplexMappingService;

    @Mock
    private LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;

    @Mock
    private MyHomeAnnouncementMappingService myHomeAnnouncementMappingService;

    @Mock
    private LhAnnouncementEnrichmentService announcementEnrichmentService;

    @Mock
    private DataPipelineExecutionStateService executionStateService;

    private final UUID executionId = UUID.randomUUID();

    private DataPipelineRunner runner;

    private DataPipelineStepResultAdapter resultAdapter;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(lhAnnouncementCatalogCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-catalog"));
        resultAdapter = new DataPipelineStepResultAdapter(JsonMapper.builder().build());
        runner = new DataPipelineRunner(
                myHomeComplexCollectionService,
                lhLeaseCatalogCollectionService,
                myHomeAnnouncementCollectionService,
                lhAnnouncementCatalogCollectionService,
                collectionService,
                myHomeComplexMappingService,
                householdEnrichmentService,
                myHomeAnnouncementMappingService,
                announcementEnrichmentService,
                resultAdapter,
                executionStateService,
                meterRegistry
        );
    }

    @Test
    void 완료된_공고_수집_단계별_시간을_기록한다() {
        givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        for (DataPipelineStep step : DataPipelineType.ANNOUNCEMENT_COLLECTION.steps()) {
            assertThat(meterRegistry.find("ingest.pipeline.step")
                    .tags("step", step.name(), "result", "completed").timer()).isNotNull();
        }
    }

    @Test
    void 성공한_단계의_실행_보고서를_완료_이력으로_전달한다() {
        ExternalDataCollectionReport report = collectionReport("myhome-announcement");
        when(myHomeAnnouncementCollectionService.collect(any())).thenReturn(report);
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(executionStateService).completeStep(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                resultAdapter.adapt(report).serverResponse()
        );
    }

    @Test
    void 단지_수집_단계를_의존_순서대로_실행한다() {
        givenSuccessfulComplexCollectionReports();

        runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId);

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

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(myHomeAnnouncementCollectionService).collect(new MyHomeAnnouncementCollectionRequest(500, 1_000));
    }

    @Test
    void 공고_수집_단계를_의존_순서대로_실행한다() {
        givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        InOrder order = inOrder(
                myHomeAnnouncementCollectionService,
                lhAnnouncementCatalogCollectionService,
                collectionService
        );
        order.verify(myHomeAnnouncementCollectionService).collect(any());
        order.verify(lhAnnouncementCatalogCollectionService).collect();
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verify(myHomeComplexCollectionService, never()).collect(any());
    }

    @Test
    void 단지_정제_단계를_의존_순서대로_실행한다() {
        givenSuccessfulComplexRefinementReports();

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

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

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId);

        InOrder order = inOrder(
                myHomeAnnouncementMappingService,
                announcementEnrichmentService
        );
        order.verify(myHomeAnnouncementMappingService).mapAll();
        order.verify(announcementEnrichmentService).enrichAll();
        verify(myHomeComplexMappingService, never()).mapAll();
    }

    @Test
    void 마지막_단계에_매칭되지_않은_행이_있어도_주의를_남기고_완료한다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(0));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 0, 0, 0, 1, 0));

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

        verify(executionStateService).completeStepWithWarnings(
                eq(executionId),
                eq(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS),
                any()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tags("step", "ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS", "result", "completed_with_warnings")
                .timer().count()).isOne();
    }

    @Test
    void LH_단지는_매칭됐지만_주택형이_누락되면_주의를_남긴다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(0));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(LhHousingTypeHouseholdEnrichmentReport.matched(0, 0, 1));

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

        verify(executionStateService).completeStepWithWarnings(
                eq(executionId),
                eq(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS), any()
        );
    }

    @Test
    void 마이홈_수집이_일부_실패해도_뒤의_LH_수집을_실행한다() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenReturn(new MyHomeComplexCollectionReport("myhome-complex", 10, 1, 20));
        when(lhLeaseCatalogCollectionService.collect(any()))
                .thenReturn(collectionReport("lh-lease-catalog"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_COMPLEXES);

        verify(lhLeaseCatalogCollectionService).collect(any());
    }

    @Test
    void 마이홈_단지_정제에_누락_행이_있어도_LH_세대수_보강을_실행한다() {
        when(myHomeComplexMappingService.mapAll()).thenReturn(complexMappingReport(2));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(new LhHousingTypeHouseholdEnrichmentReport(1, 1, 1, 0, 0, 0));

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

        verify(executionStateService).completeStepWithWarnings(
                eq(executionId),
                eq(DataPipelineStep.MAP_MYHOME_COMPLEXES), any()
        );
        verify(householdEnrichmentService).enrichAll();
    }

    @Test
    void 마이홈_공고_정제에_누락_행이_있어도_LH_공고_보강을_실행한다() {
        MyHomeAnnouncementMappingReport partialFailure =
                MyHomeAnnouncementMappingReport.failedRows(2);
        when(myHomeAnnouncementMappingService.mapAll()).thenReturn(partialFailure);
        when(announcementEnrichmentService.enrichAll())
                .thenReturn(LhAnnouncementEnrichmentReport.empty());

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId);

        verify(executionStateService).completeStepWithWarnings(
                eq(executionId),
                eq(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS), any()
        );
        verify(announcementEnrichmentService).enrichAll();
    }

    @Test
    void 공고_보강에_누락된_원천이_있으면_주의를_남긴다() {
        when(myHomeAnnouncementMappingService.mapAll())
                .thenReturn(new MyHomeAnnouncementMappingReport(1, 0, 0, 1, 0, 0, 0, 0));
        when(announcementEnrichmentService.enrichAll())
                .thenReturn(LhAnnouncementEnrichmentReport.failed());

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId);

        verify(executionStateService).completeStepWithWarnings(
                eq(executionId),
                eq(DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS), any()
        );
    }

    @Test
    void 단지_정제의_운영_실패는_누락과_달리_파이프라인을_실패로_종료한다() {
        when(myHomeComplexMappingService.mapAll())
                .thenReturn(MyHomeComplexMappingReport.operationalFailedRows(1));
        when(householdEnrichmentService.enrichAll())
                .thenReturn(LhHousingTypeHouseholdEnrichmentReport.empty(0));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);

        verify(householdEnrichmentService).enrichAll();
    }

    @Test
    void LH_공급에서_호출_제한에_도달하면_상세는_외부_호출_없이_건너뛴다() {
        when(myHomeAnnouncementCollectionService.collect(any())).thenReturn(collectionReport("myhome-announcement"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)).thenReturn(
                new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 1, 0, 1));

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(collectionService, never()).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verify(executionStateService).skipStep(
                eq(executionId), eq(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS), any(), eq("{}")
        );
    }

    @Test
    void LH_공급에_호출_제한과_일반_오류가_함께_있으면_부분_실패를_기록하고_상세를_건너뛴다() {
        ExternalDataCollectionReport mixedFailure = new ExternalDataCollectionReport(
                "lh-announcement-supply", 0, 2, 2, 0, 1
        );
        when(myHomeAnnouncementCollectionService.collect(any())).thenReturn(collectionReport("myhome-announcement"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)).thenReturn(mixedFailure);
        org.mockito.Mockito.lenient().when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);

        verify(executionStateService).recordPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                resultAdapter.adapt(mixedFailure).serverResponse()
        );
        verify(collectionService, never()).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verify(executionStateService).skipStep(
                eq(executionId), eq(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS), any(), eq("{}")
        );
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
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(executionStateService).skipStep(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                resultAdapter.adapt(rateLimited).serverResponse()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tag("result", "rate_limited").timer().count()).isOne();
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
    }

    @Test
    void 마이홈_공고_수집이_일부_실패해도_LH_증분_수집을_실행한다() {
        ExternalDataCollectionReport partialFailure = new ExternalDataCollectionReport(
                "myhome-announcement",
                0,
                2,
                4,
                0,
                1
        );
        when(myHomeAnnouncementCollectionService.collect(any())).thenReturn(partialFailure);
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class);

        verify(executionStateService).recordPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                resultAdapter.adapt(partialFailure).serverResponse()
        );
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
    }

    @Test
    void 여러_단계가_부분_실패해도_모두_실행하고_최초_실패를_대표로_반환한다() {
        when(myHomeAnnouncementCollectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("myhome-announcement", 0, 1, 1));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 1));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 0, 1, 1));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        InOrder order = inOrder(
                myHomeAnnouncementCollectionService,
                lhAnnouncementCatalogCollectionService,
                collectionService
        );
        order.verify(myHomeAnnouncementCollectionService).collect(any());
        order.verify(lhAnnouncementCatalogCollectionService).collect();
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
    }

    @Test
    void 예상하지_못한_예외가_발생하면_이후_단계를_실행하지_않는다() {
        when(myHomeComplexCollectionService.collect(any()))
                .thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId))
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

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

        verify(executionStateService).skipStep(
                executionId,
                DataPipelineStep.MAP_MYHOME_COMPLEXES,
                "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                resultAdapter.adapt(rateLimited).serverResponse()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tag("result", "rate_limited").timer().count()).isOne();
        verify(householdEnrichmentService).enrichAll();
    }

    @Test
    void 소유권을_잃으면_중지_조회와_단계_시작_전에_실행을_거절한다() {
        Lease lease = mock(Lease.class);
        DataPipelineExecutionMonitor monitor = mock(DataPipelineExecutionMonitor.class);
        doThrow(new IngestOwnershipLostException()).when(lease).verifyHeld();

        try (var ignored = IngestExecutionScope.open(lease, monitor)) {
            assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        verifyNoInteractions(
                monitor, executionStateService, myHomeComplexCollectionService, lhLeaseCatalogCollectionService
        );
    }

    @Test
    void 다음_단계_시작_전에_소유권을_확인하고_중지_요청을_반영한다() {
        Lease lease = mock(Lease.class);
        DataPipelineExecutionMonitor monitor = mock(DataPipelineExecutionMonitor.class);
        doNothing().doThrow(new DataPipelineStoppedException()).when(monitor).checkStopRequested();
        MyHomeComplexCollectionReport report = new MyHomeComplexCollectionReport("myhome-complex", 1, 0, 1);
        when(myHomeComplexCollectionService.collect(any())).thenReturn(report);

        try (var ignored = IngestExecutionScope.open(lease, monitor)) {
            assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }

        InOrder order = inOrder(lease, monitor, executionStateService, myHomeComplexCollectionService);
        order.verify(lease).verifyHeld();
        order.verify(monitor).checkStopRequested();
        order.verify(executionStateService).startStep(executionId, DataPipelineStep.COLLECT_MYHOME_COMPLEXES);
        order.verify(myHomeComplexCollectionService).collect(any());
        order.verify(executionStateService).completeStep(
                executionId, DataPipelineStep.COLLECT_MYHOME_COMPLEXES, resultAdapter.adapt(report).serverResponse()
        );
        order.verify(lease).verifyHeld();
        order.verify(monitor).checkStopRequested();
        verify(executionStateService, never()).startStep(executionId, DataPipelineStep.COLLECT_LH_LEASE_CATALOG);
        verifyNoInteractions(lhLeaseCatalogCollectionService);
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
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
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
