package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DataPipelineExecutionStateService.class, JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataPipelinePartialFailureStateTransitionIntegrationTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-17T12:00:00Z");

    @Autowired
    private DataPipelineExecutionRepository executionRepository;

    @Autowired
    private DataPipelineExecutionStateService executionStateService;

    @Test
    void 행별_누락이_있어도_후속_단계를_실행하고_주의_상태로_완료한다() {
        MyHomeComplexMappingService mappingService = mock(MyHomeComplexMappingService.class);
        LhHousingTypeHouseholdEnrichmentService enrichmentService =
                mock(LhHousingTypeHouseholdEnrichmentService.class);
        when(mappingService.mapAll()).thenReturn(MyHomeComplexMappingReport.failedRows(1));
        when(enrichmentService.enrichAll()).thenReturn(
                LhHousingTypeHouseholdEnrichmentReport.matched(1, 0, 0)
        );
        DataPipelineRunner runner = runner(mappingService, enrichmentService);
        DataPipelineExecutionLock.Lease lease = mock(DataPipelineExecutionLock.Lease.class);
        DataPipelineExecutionService service = service(runner, lease);

        service.start(DataPipelineType.COMPLEX_REFINEMENT);

        var execution = executionRepository
                .findFirstByTypeOrderByIdDesc(DataPipelineType.COMPLEX_REFINEMENT)
                .orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WARNINGS);
        assertThat(execution.getFailedStep()).isNull();
        assertThat(execution.getPartiallyFailedSteps()).singleElement().satisfies(warning -> {
            assertThat(warning.getStep()).isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);
            assertThat(warning.getReport()).contains("\"failedSourceRowCount\":1");
        });
        assertThat(execution.getCompletedSteps())
                .containsExactly(DataPipelineStep.MAP_MYHOME_COMPLEXES,
                        DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
        verify(enrichmentService).enrichAll();
        verify(lease).close();
    }

    @Test
    void LH_주택형만_매칭되지_않아도_완료_주의_상태와_보고서를_저장한다() {
        MyHomeComplexMappingService mappingService = mock(MyHomeComplexMappingService.class);
        LhHousingTypeHouseholdEnrichmentService enrichmentService =
                mock(LhHousingTypeHouseholdEnrichmentService.class);
        when(mappingService.mapAll()).thenReturn(MyHomeComplexMappingReport.failedRows(0));
        when(enrichmentService.enrichAll()).thenReturn(
                LhHousingTypeHouseholdEnrichmentReport.matched(0, 0, 1)
        );
        DataPipelineExecutionService service = service(
                runner(mappingService, enrichmentService),
                mock(DataPipelineExecutionLock.Lease.class)
        );

        service.start(DataPipelineType.COMPLEX_REFINEMENT);

        var execution = executionRepository
                .findFirstByTypeOrderByIdDesc(DataPipelineType.COMPLEX_REFINEMENT)
                .orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WARNINGS);
        assertThat(execution.getPartiallyFailedSteps()).singleElement().satisfies(warning -> {
            assertThat(warning.getStep())
                    .isEqualTo(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
            assertThat(warning.getReport()).contains("\"unmatchedHousingTypeCount\":1");
        });
    }

    @Test
    void 단지_정제의_운영_실패는_후속_단계를_실행한_뒤_FAILED로_종료한다() {
        MyHomeComplexMappingService mappingService = mock(MyHomeComplexMappingService.class);
        LhHousingTypeHouseholdEnrichmentService enrichmentService =
                mock(LhHousingTypeHouseholdEnrichmentService.class);
        when(mappingService.mapAll()).thenReturn(MyHomeComplexMappingReport.operationalFailedRows(1));
        when(enrichmentService.enrichAll()).thenReturn(
                LhHousingTypeHouseholdEnrichmentReport.matched(1, 0, 0)
        );
        DataPipelineRunner runner = runner(mappingService, enrichmentService);
        DataPipelineExecutionLock.Lease lease = mock(DataPipelineExecutionLock.Lease.class);
        DataPipelineExecutionService service = service(runner, lease);

        service.start(DataPipelineType.COMPLEX_REFINEMENT);

        var execution = executionRepository
                .findFirstByTypeOrderByIdDesc(DataPipelineType.COMPLEX_REFINEMENT)
                .orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(execution.getFailedStep()).isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);
        assertThat(execution.getCompletedSteps())
                .containsExactly(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
        verify(enrichmentService).enrichAll();
    }

    @Test
    void 네_단계_파이프라인의_첫_단계가_부분_실패해도_나머지_단계를_모두_완료한다() {
        MyHomeAnnouncementCollectionService myHomeService =
                mock(MyHomeAnnouncementCollectionService.class);
        LhAnnouncementExternalCollectionService collectionService =
                mock(LhAnnouncementExternalCollectionService.class);
        when(myHomeService.collect(any())).thenReturn(
                new ExternalDataCollectionReport("myhome-announcement", 2, 1, 3)
        );
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)).thenReturn(
                ExternalDataCollectionReport.empty("lh-announcement-supply")
        );
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)).thenReturn(
                ExternalDataCollectionReport.empty("lh-announcement-detail")
        );
        DataPipelineRunner runner = runner(myHomeService, collectionService);
        DataPipelineExecutionLock.Lease lease = mock(DataPipelineExecutionLock.Lease.class);
        DataPipelineExecutionService service = service(runner, lease);

        service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        var execution = executionRepository
                .findFirstByTypeOrderByIdDesc(DataPipelineType.ANNOUNCEMENT_COLLECTION)
                .orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(execution.getFailedStep())
                .isEqualTo(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
        assertThat(execution.getFailureServerResponse())
                .contains("\"failedRequestCount\":1");
        assertThat(execution.getCompletedSteps()).containsExactly(
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        );
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verify(lease).close();
    }

    @Test
    void 여러_부분_실패를_저장하고_다음_실행에는_실패_상태를_넘기지_않는다() {
        MyHomeAnnouncementCollectionService myHomeService =
                mock(MyHomeAnnouncementCollectionService.class);
        LhAnnouncementExternalCollectionService collectionService =
                mock(LhAnnouncementExternalCollectionService.class);
        when(myHomeService.collect(any())).thenReturn(
                new ExternalDataCollectionReport("myhome-announcement", 0, 1, 1),
                ExternalDataCollectionReport.empty("myhome-announcement")
        );
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)).thenReturn(
                new ExternalDataCollectionReport("lh-announcement-supply", 0, 1, 1),
                ExternalDataCollectionReport.empty("lh-announcement-supply")
        );
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)).thenReturn(
                new ExternalDataCollectionReport("lh-announcement-detail", 0, 1, 1),
                ExternalDataCollectionReport.empty("lh-announcement-detail")
        );
        DataPipelineExecutionService service = service(
                runner(myHomeService, collectionService),
                mock(DataPipelineExecutionLock.Lease.class)
        );

        var failed = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var first = executionRepository.findByExecutionId(failed.executionId()).orElseThrow();

        assertThat(first.getStatus()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(first.getFailedStep()).isEqualTo(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
        assertThat(first.getPartiallyFailedSteps()).extracting("step").containsExactly(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        );
        assertThat(first.getCompletedSteps()).containsExactly(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG);

        var completed = service.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        var second = executionRepository.findByExecutionId(completed.executionId()).orElseThrow();

        assertThat(second.getStatus()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(second.getPartiallyFailedSteps()).isEmpty();
        assertThat(second.getCompletedSteps())
                .containsExactlyElementsOf(DataPipelineType.ANNOUNCEMENT_COLLECTION.steps());
    }

    @Test
    void 마지막_수집_부분_실패는_통합_정제를_막고_독립_정제로_복구할_수_있다() {
        var myHomeService = mock(MyHomeAnnouncementCollectionService.class);
        var collectionService = mock(LhAnnouncementExternalCollectionService.class);
        var mappingService = mock(MyHomeAnnouncementMappingService.class);
        var enrichmentService = mock(LhAnnouncementEnrichmentService.class);
        when(myHomeService.collect(any())).thenReturn(ExternalDataCollectionReport.empty("myhome-announcement"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(ExternalDataCollectionReport.empty("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 1, 1, 1));
        var service = service(runner(myHomeService, collectionService, mappingService, enrichmentService),
                mock(DataPipelineExecutionLock.Lease.class));

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        var failed = service.find(accepted.executionId());

        assertThat(failed.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(failed.currentStepIndex()).isEqualTo(4);
        assertThat(failed.totalStepCount()).isEqualTo(6);
        assertThat(failed.partiallyFailedSteps()).singleElement()
                .extracting("step").isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
        verifyNoInteractions(mappingService, enrichmentService);

        when(mappingService.mapAll()).thenReturn(new MyHomeAnnouncementMappingReport(1, 0, 0, 1, 0, 0, 0, 0));
        when(enrichmentService.enrichAll()).thenReturn(LhAnnouncementEnrichmentReport.empty());
        var recovered = service.start(DataPipelineType.ANNOUNCEMENT_REFINEMENT);

        assertThat(recovered.executionId()).isNotEqualTo(failed.executionId());
        assertThat(service.find(recovered.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(service.find(failed.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        verify(myHomeService).collect(any());
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        verify(mappingService).mapAll();
    }

    @Test
    void 통합_수집_호출_제한은_정제_건너뜀_사유와_부분_완료를_저장한다() {
        var myHomeService = mock(MyHomeAnnouncementCollectionService.class);
        var collectionService = mock(LhAnnouncementExternalCollectionService.class);
        var mappingService = mock(MyHomeAnnouncementMappingService.class);
        var enrichmentService = mock(LhAnnouncementEnrichmentService.class);
        when(myHomeService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("myhome-announcement", 0, 1, 0, 0, 1));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .thenReturn(ExternalDataCollectionReport.empty("lh-announcement-supply"));
        when(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .thenReturn(ExternalDataCollectionReport.empty("lh-announcement-detail"));
        var service = service(runner(myHomeService, collectionService, mappingService, enrichmentService),
                mock(DataPipelineExecutionLock.Lease.class));

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        var result = service.find(accepted.executionId());

        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(result.skippedSteps()).extracting("stepName").containsExactly(
                "마이홈 공고 수집", "마이홈 공고 정제", "LH 공고 상세·공급 정보 보강");
        assertThat(result.skippedSteps().get(1).reason()).contains("자동 정제를 실행하지 않았습니다");
        verifyNoInteractions(mappingService, enrichmentService);
    }

    @ParameterizedTest
    @CsvSource({"COMPLEX_SYNC, 3, 4", "ANNOUNCEMENT_SYNC, 5, 6"})
    void 통합_실행의_정제_순서와_완료를_저장하고_조회한다(
            DataPipelineType type, int refinementIndex, int totalSteps
    ) {
        UUID executionId = UUID.randomUUID();
        executionStateService.create(executionId, type, STARTED_AT);
        var mapper = new DataPipelineExecutionMapper(JsonMapper.builder().build());
        for (var step : type.steps()) {
            executionStateService.startStep(executionId, step);
            if (type.sequenceOf(step) == refinementIndex) {
                var response = mapper.response(executionRepository.findByExecutionId(executionId).orElseThrow());
                assertThat(response.currentStepIndex()).isEqualTo(refinementIndex);
                assertThat(response.totalStepCount()).isEqualTo(totalSteps);
            }
            executionStateService.completeStep(executionId, step, "{}");
        }
        executionStateService.complete(executionId, STARTED_AT.plusSeconds(1));

        assertThat(executionRepository.findByExecutionId(executionId).orElseThrow().getStatus())
                .isEqualTo(DataPipelineExecutionStatus.COMPLETED);
    }

    private DataPipelineExecutionService service(
            DataPipelineRunner runner,
            DataPipelineExecutionLock.Lease lease
    ) {
        DataPipelineExecutionLock executionLock = mock(DataPipelineExecutionLock.class);
        when(executionLock.tryAcquire(any(java.util.UUID.class))).thenReturn(Optional.of(lease));
        ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        doReturn(heartbeatTask).when(heartbeatExecutor)
                .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        return new DataPipelineExecutionService(
                runner,
                executionLock,
                executionRepository,
                executionStateService,
                Runnable::run,
                heartbeatExecutor,
                Clock.fixed(STARTED_AT, ZoneOffset.UTC),
                new DataPipelineExecutionMapper(JsonMapper.builder().build())
        );
    }

    private DataPipelineRunner runner(
            MyHomeComplexMappingService mappingService,
            LhHousingTypeHouseholdEnrichmentService enrichmentService
    ) {
        return new DataPipelineRunner(
                mock(MyHomeComplexCollectionService.class),
                mock(LhLeaseCatalogCollectionService.class),
                mock(MyHomeAnnouncementCollectionService.class),
                mock(LhAnnouncementCatalogCollectionService.class),
                mock(LhAnnouncementExternalCollectionService.class),
                mappingService,
                enrichmentService,
                mock(MyHomeAnnouncementMappingService.class),
                mock(LhAnnouncementEnrichmentService.class),
                new DataPipelineStepResultAdapter(JsonMapper.builder().build()),
                executionStateService,
                new SimpleMeterRegistry()
        );
    }

    private DataPipelineRunner runner(
            MyHomeAnnouncementCollectionService myHomeService,
            LhAnnouncementExternalCollectionService collectionService
    ) {
        return runner(myHomeService, collectionService,
                mock(MyHomeAnnouncementMappingService.class), mock(LhAnnouncementEnrichmentService.class));
    }

    private DataPipelineRunner runner(
            MyHomeAnnouncementCollectionService myHomeService,
            LhAnnouncementExternalCollectionService collectionService,
            MyHomeAnnouncementMappingService mappingService,
            LhAnnouncementEnrichmentService enrichmentService
    ) {
        return new DataPipelineRunner(
                mock(MyHomeComplexCollectionService.class),
                mock(LhLeaseCatalogCollectionService.class),
                myHomeService,
                successfulCatalogService(),
                collectionService,
                mock(MyHomeComplexMappingService.class),
                mock(LhHousingTypeHouseholdEnrichmentService.class),
                mappingService,
                enrichmentService,
                new DataPipelineStepResultAdapter(JsonMapper.builder().build()),
                executionStateService,
                new SimpleMeterRegistry()
        );
    }
    private LhAnnouncementCatalogCollectionService successfulCatalogService() {
        var service = mock(LhAnnouncementCatalogCollectionService.class);
        when(service.collect()).thenReturn(new com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport(
                "lh-announcement-catalog", 1, 0, 1
        ));
        return service;
    }

}
