package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
import com.toadzip.backend.ingest.collection.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(DataPipelineExecutionStateService.class)
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
    void 세_단계_파이프라인의_첫_단계가_부분_실패해도_나머지_단계를_모두_완료한다() {
        MyHomeAnnouncementCollectionService myHomeService =
                mock(MyHomeAnnouncementCollectionService.class);
        LhAnnouncementSupplyCollectionService supplyService =
                mock(LhAnnouncementSupplyCollectionService.class);
        LhAnnouncementDetailCollectionService detailService =
                mock(LhAnnouncementDetailCollectionService.class);
        when(myHomeService.collect(any())).thenReturn(
                new ExternalDataCollectionReport("myhome-announcement", 2, 1, 3)
        );
        when(supplyService.collect()).thenReturn(
                ExternalDataCollectionReport.empty("lh-announcement-supply")
        );
        when(detailService.collect()).thenReturn(
                ExternalDataCollectionReport.empty("lh-announcement-detail")
        );
        DataPipelineRunner runner = runner(myHomeService, supplyService, detailService);
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
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        );
        verify(supplyService).collect();
        verify(detailService).collect();
        verify(lease).close();
    }

    private DataPipelineExecutionService service(
            DataPipelineRunner runner,
            DataPipelineExecutionLock.Lease lease
    ) {
        DataPipelineExecutionLock executionLock = mock(DataPipelineExecutionLock.class);
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
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
                mock(LhAnnouncementSupplyCollectionService.class),
                mock(LhAnnouncementDetailCollectionService.class),
                mappingService,
                enrichmentService,
                mock(MyHomeAnnouncementMappingService.class),
                mock(LhAnnouncementEnrichmentService.class),
                new DataPipelineStepResultAdapter(JsonMapper.builder().build()),
                new SimpleMeterRegistry()
        );
    }

    private DataPipelineRunner runner(
            MyHomeAnnouncementCollectionService myHomeService,
            LhAnnouncementSupplyCollectionService supplyService,
            LhAnnouncementDetailCollectionService detailService
    ) {
        return new DataPipelineRunner(
                mock(MyHomeComplexCollectionService.class),
                mock(LhLeaseCatalogCollectionService.class),
                myHomeService,
                supplyService,
                detailService,
                mock(MyHomeComplexMappingService.class),
                mock(LhHousingTypeHouseholdEnrichmentService.class),
                mock(MyHomeAnnouncementMappingService.class),
                mock(LhAnnouncementEnrichmentService.class),
                new DataPipelineStepResultAdapter(JsonMapper.builder().build()),
                new SimpleMeterRegistry()
        );
    }
}
