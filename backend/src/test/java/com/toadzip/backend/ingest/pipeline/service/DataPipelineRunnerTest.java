package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.domain.IngestStepReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.gh.collection.service.GhAnnouncementCollectionService;
import com.toadzip.backend.ingest.gh.refinement.service.GhAnnouncementRefinementService;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.lh.refinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementRefinementService;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.sh.collection.service.ShAnnouncementCollectionService;
import com.toadzip.backend.ingest.sh.refinement.service.ShAnnouncementRefinementService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private LhAnnouncementCatalogCollectionService lhAnnouncementCatalogCollectionService;

    @Mock
    private LhAnnouncementExternalCollectionService collectionService;

    @Mock
    private MyHomeComplexMappingService myHomeComplexMappingService;

    @Mock
    private LhHousingTypeHouseholdEnrichmentService householdEnrichmentService;

    @Mock
    private LhAnnouncementRefinementService lhAnnouncementRefinementService;

    @Mock
    private DataPipelineExecutionStateService executionStateService;

    private final UUID executionId = UUID.randomUUID();

    private DataPipelineRunner runner;

    private final ShAnnouncementCollectionService shAnnouncementCollectionService =
            org.mockito.Mockito.mock(ShAnnouncementCollectionService.class);
    private final GhAnnouncementCollectionService ghAnnouncementCollectionService =
            mock(GhAnnouncementCollectionService.class);

    private DataPipelineStepResultAdapter resultAdapter;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(lhAnnouncementCatalogCollectionService.collect())
                .thenReturn(collectionReport("lh-announcement-catalog"));
        resultAdapter = new DataPipelineStepResultAdapter(JsonMapper.builder().build());
        runner = new DataPipelineRunner(
                new DataPipelineStepExecutor(
                        myHomeComplexCollectionService,
                        lhLeaseCatalogCollectionService,
                        lhAnnouncementCatalogCollectionService,
                        collectionService,
                        myHomeComplexMappingService,
                        householdEnrichmentService,
                        lhAnnouncementRefinementService,
                        resultAdapter,
                        shAnnouncementCollectionService,
                        ghAnnouncementCollectionService,
                        mock(ShAnnouncementRefinementService.class),
                        mock(GhAnnouncementRefinementService.class)
                ),
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
        ExternalDataCollectionReport report = collectionReport("lh-announcement-catalog-result");
        when(lhAnnouncementCatalogCollectionService.collect()).thenReturn(report);
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(executionStateService).completeStep(
                executionId,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                resultAdapter.adapt(report).serverResponse()
        );
    }

    @Test
    void 단지_수집_단계를_의존_순서대로_실행한다() {
        List<IngestStepReport> reports = givenSuccessfulComplexCollectionReports();

        runner.run(DataPipelineType.COMPLEX_COLLECTION, executionId);

        assertThat(completedSteps()).containsExactly(
                completed(DataPipelineStep.COLLECT_MYHOME_COMPLEXES, reports.get(0)),
                completed(DataPipelineStep.COLLECT_LH_LEASE_CATALOG, reports.get(1))
        );
        verifyNoInteractions(lhAnnouncementCatalogCollectionService, collectionService);
    }

    @Test
    void 공고_수집_단계를_의존_순서대로_실행한다() {
        List<IngestStepReport> reports = givenSuccessfulAnnouncementCollectionReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        assertThat(completedSteps()).containsExactly(
                completed(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG, reports.get(0)),
                completed(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES, reports.get(1)),
                completed(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS, reports.get(2))
        );
        verify(myHomeComplexCollectionService, never()).collect(any());
    }

    @Test
    void 단지_정제_단계를_의존_순서대로_실행한다() {
        List<IngestStepReport> reports = givenSuccessfulComplexRefinementReports();

        runner.run(DataPipelineType.COMPLEX_REFINEMENT, executionId);

        assertThat(completedSteps()).containsExactly(
                completed(DataPipelineStep.MAP_MYHOME_COMPLEXES, reports.get(0)),
                completed(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS, reports.get(1))
        );
        verifyNoInteractions(lhAnnouncementRefinementService);
    }

    @Test
    void 공고_정제_단계를_의존_순서대로_실행한다() {
        List<IngestStepReport> reports = givenSuccessfulAnnouncementRefinementReports();

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId);

        assertThat(completedSteps()).containsExactly(
                completed(DataPipelineStep.MAP_LH_ANNOUNCEMENTS, reports.get(0))
        );
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
    void LH_공고_정제에_실패한_공고가_있으면_부분_실패로_기록한다() {
        LhAnnouncementRefinementReport partialFailure = lhRefinementReport(Map.of(), 2);
        when(lhAnnouncementRefinementService.refineAll()).thenReturn(partialFailure);

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.MAP_LH_ANNOUNCEMENTS);

        verify(executionStateService).recordPartialFailure(
                executionId,
                DataPipelineStep.MAP_LH_ANNOUNCEMENTS,
                resultAdapter.adapt(partialFailure).serverResponse()
        );
        verify(executionStateService, never()).completeStep(eq(executionId),
                eq(DataPipelineStep.MAP_LH_ANNOUNCEMENTS), any());
    }

    @Test
    void LH_공고_정제의_보류는_실패가_아니어서_정제_단계를_완료로_기록한다() {
        LhAnnouncementRefinementReport held = lhRefinementReport(Map.of("공급 원천 없음", 3), 0);
        when(lhAnnouncementRefinementService.refineAll()).thenReturn(held);

        runner.run(DataPipelineType.ANNOUNCEMENT_REFINEMENT, executionId);

        assertThat(completedSteps()).containsExactly(completed(DataPipelineStep.MAP_LH_ANNOUNCEMENTS, held));
        verify(executionStateService, never()).recordPartialFailure(eq(executionId),
                eq(DataPipelineStep.MAP_LH_ANNOUNCEMENTS), any());
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
    void LH_공고목록이_호출_제한이면_목록을_건너뛰고_공급과_상세도_외부_호출_없이_건너뛴다() {
        ExternalDataCollectionReport rateLimited = new ExternalDataCollectionReport(
                "lh-announcement-catalog",
                0,
                1,
                3,
                0,
                1
        );
        when(lhAnnouncementCatalogCollectionService.collect()).thenReturn(rateLimited);

        runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId);

        verify(executionStateService).skipStep(
                executionId,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.",
                resultAdapter.adapt(rateLimited).serverResponse()
        );
        assertThat(meterRegistry.get("ingest.pipeline.step")
                .tag("result", "rate_limited").timer().count()).isOne();
        verifyNoInteractions(collectionService);
    }

    @Test
    void LH_공고목록이_일부_실패해도_LH_공급과_상세_수집을_실행한다() {
        ExternalDataCollectionReport partialFailure = new ExternalDataCollectionReport(
                "lh-announcement-catalog", 0, 2, 4);
        when(lhAnnouncementCatalogCollectionService.collect()).thenReturn(partialFailure);
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(collectionReport("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(collectionReport("lh-announcement-detail"));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class);

        verify(executionStateService).recordPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                resultAdapter.adapt(partialFailure).serverResponse()
        );
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
    }

    @Test
    void 여러_단계가_부분_실패해도_모두_실행하고_최초_실패를_대표로_반환한다() {
        when(lhAnnouncementCatalogCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-catalog", 0, 1, 1));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 1));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 0, 1, 1));

        assertThatThrownBy(() -> runner.run(DataPipelineType.ANNOUNCEMENT_COLLECTION, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step")
                .isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);

        InOrder order = inOrder(
                lhAnnouncementCatalogCollectionService,
                collectionService
        );
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

    @Test
    void 단지_통합_실행은_수집_뒤_정제와_보강까지_순서대로_실행한다() {
        DataPipelineType type = DataPipelineType.fromPathValue("complex-sync");
        givenSuccessfulComplexCollectionReports();
        givenSuccessfulComplexRefinementReports();

        runner.run(type, executionId);

        InOrder order = inOrder(myHomeComplexCollectionService, lhLeaseCatalogCollectionService,
                myHomeComplexMappingService, householdEnrichmentService);
        order.verify(myHomeComplexCollectionService).collect(any());
        order.verify(lhLeaseCatalogCollectionService).collect(any());
        order.verify(myHomeComplexMappingService).mapAll();
        order.verify(householdEnrichmentService).enrichAll();
    }

    @Test
    void 공고_통합_실행은_TTL_생략만_있으면_정제를_이어간다() {
        DataPipelineType type = DataPipelineType.fromPathValue("announcement-sync");
        givenSuccessfulAnnouncementCollectionReports();
        givenSuccessfulAnnouncementRefinementReports();
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 0, 0, 0, 3, 0));

        runner.run(type, executionId);

        InOrder order = inOrder(lhAnnouncementCatalogCollectionService,
                collectionService, lhAnnouncementRefinementService);
        order.verify(lhAnnouncementCatalogCollectionService).collect();
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        order.verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        order.verify(lhAnnouncementRefinementService).refineAll();
    }

    @Test
    void 통합_수집의_부분_실패는_후속_수집을_유지하고_자동_정제를_막는다() {
        DataPipelineType type = DataPipelineType.fromPathValue("announcement-sync");
        givenSuccessfulAnnouncementCollectionReports();
        when(lhAnnouncementCatalogCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-catalog", 1, 1, 1));

        assertThatThrownBy(() -> runner.run(type, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step").isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);

        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verifyNoInteractions(lhAnnouncementRefinementService);
        verify(executionStateService, never()).startStep(executionId, DataPipelineStep.MAP_LH_ANNOUNCEMENTS);
    }

    @Test
    void LH_호출_제한은_후속_외부_수집과_자동_정제를_모두_막는다() {
        DataPipelineType type = DataPipelineType.fromPathValue("announcement-sync");
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 0, 0, 1));

        runner.run(type, executionId);

        verify(collectionService, never()).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verifyNoInteractions(lhAnnouncementRefinementService);
        verify(executionStateService).skipStep(eq(executionId),
                eq(DataPipelineStep.MAP_LH_ANNOUNCEMENTS), any(), eq("{}"));
    }

    @Test
    void 수집_직후_중지_요청은_통합_실행의_정제_시작_전에_반영된다() {
        DataPipelineType type = DataPipelineType.fromPathValue("complex-sync");
        givenSuccessfulComplexCollectionReports();
        Lease lease = mock(Lease.class);
        DataPipelineExecutionMonitor monitor = mock(DataPipelineExecutionMonitor.class);
        doNothing().doNothing().doThrow(new DataPipelineStoppedException()).when(monitor).checkStopRequested();

        try (var ignored = IngestExecutionScope.open(lease, monitor)) {
            assertThatThrownBy(() -> runner.run(type, executionId))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }

        verifyNoInteractions(myHomeComplexMappingService, householdEnrichmentService);
        verify(executionStateService, never()).startStep(executionId, DataPipelineStep.MAP_MYHOME_COMPLEXES);
    }

    @Test
    void 통합_실행의_정제_운영_실패는_기존처럼_보강_후_실패로_종료한다() {
        givenSuccessfulComplexCollectionReports();
        when(myHomeComplexMappingService.mapAll()).thenReturn(MyHomeComplexMappingReport.operationalFailedRows(1));
        when(householdEnrichmentService.enrichAll()).thenReturn(LhHousingTypeHouseholdEnrichmentReport.empty(0));

        assertThatThrownBy(() -> runner.run(DataPipelineType.COMPLEX_SYNC, executionId))
                .isInstanceOf(DataPipelinePartialFailureException.class)
                .extracting("step").isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);

        verify(householdEnrichmentService).enrichAll();
    }

    /** 단계 순서대로 각 단계 서비스가 돌려줄 보고서를 정하고 그 보고서를 같은 순서로 돌려준다. */
    private List<IngestStepReport> givenSuccessfulComplexCollectionReports() {
        var myHomeComplexes = new MyHomeComplexCollectionReport("myhome-complex", 1, 0, 1);
        var lhLeaseCatalog = collectionReport("lh-lease-catalog");
        when(myHomeComplexCollectionService.collect(any())).thenReturn(myHomeComplexes);
        when(lhLeaseCatalogCollectionService.collect(any())).thenReturn(lhLeaseCatalog);
        return List.of(myHomeComplexes, lhLeaseCatalog);
    }

    private List<IngestStepReport> givenSuccessfulAnnouncementCollectionReports() {
        var lhSupplies = collectionReport("lh-announcement-supply");
        var lhDetails = collectionReport("lh-announcement-detail");
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)).thenReturn(lhSupplies);
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)).thenReturn(lhDetails);
        // LH 공고목록 보고서는 setUp에서 정한다.
        return List.of(collectionReport("lh-announcement-catalog"), lhSupplies, lhDetails);
    }

    private List<IngestStepReport> givenSuccessfulComplexRefinementReports() {
        var myHomeComplexes = complexMappingReport(0);
        var households = new LhHousingTypeHouseholdEnrichmentReport(1, 1, 1, 0, 0, 0);
        when(myHomeComplexMappingService.mapAll()).thenReturn(myHomeComplexes);
        when(householdEnrichmentService.enrichAll()).thenReturn(households);
        return List.of(myHomeComplexes, households);
    }

    private List<IngestStepReport> givenSuccessfulAnnouncementRefinementReports() {
        var lhAnnouncements = lhRefinementReport(Map.of(), 0);
        when(lhAnnouncementRefinementService.refineAll()).thenReturn(lhAnnouncements);
        return List.of(lhAnnouncements);
    }

    private LhAnnouncementRefinementReport lhRefinementReport(Map<String, Integer> heldCounts, int failedCount) {
        return new LhAnnouncementRefinementReport(
                1, 1, 0, 0, heldCounts, failedCount, 0, 0, 1, 0,
                new LhAnnouncementRefinementReport.ComplexCounts(1, 0, 0, 0, 0, 1, 0),
                new LhAnnouncementRefinementReport.SupplyCounts(1, 0, 0, 1, 0, 0, 1, Map.of())
        );
    }

    /** 실행 이력에 완료로 기록된 단계와 그 응답을 기록된 순서대로 돌려준다. */
    private List<CompletedStep> completedSteps() {
        ArgumentCaptor<DataPipelineStep> steps = ArgumentCaptor.forClass(DataPipelineStep.class);
        ArgumentCaptor<String> responses = ArgumentCaptor.forClass(String.class);
        verify(executionStateService, atLeastOnce()).completeStep(eq(executionId), steps.capture(), responses.capture());
        return IntStream.range(0, steps.getAllValues().size())
                .mapToObj(index -> new CompletedStep(steps.getAllValues().get(index), responses.getAllValues().get(index)))
                .toList();
    }

    private CompletedStep completed(DataPipelineStep step, IngestStepReport report) {
        return new CompletedStep(step, resultAdapter.adapt(report).serverResponse());
    }

    private record CompletedStep(DataPipelineStep step, String serverResponse) {
    }

    private ExternalDataCollectionReport collectionReport(String operation) {
        return new ExternalDataCollectionReport(operation, 1, 0, 1);
    }

    private MyHomeComplexMappingReport complexMappingReport(int failedCount) {
        return new MyHomeComplexMappingReport(1, 0, 0, 1, 0, 0, 0, failedCount);
    }
}
