package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(DataPipelineExecutionStateService.class)
class DataPipelineExecutionRepositoryTest {

    @Autowired
    private DataPipelineExecutionRepository executionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataPipelineExecutionStateService executionStateService;

    @Test
    void 다른_영속성_컨텍스트에서도_최신_실행의_단계와_상태를_조회한다() {
        DataPipelineExecution olderExecution = executionAt("2026-09-03T03:00:00Z");
        executionRepository.saveAndFlush(olderExecution);
        DataPipelineExecution latestExecution = executionAt("2026-09-03T02:00:00Z");
        latestExecution.startStep(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
        latestExecution.completeStep(
                DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                "{\"mappedSourceRowCount\":3}"
        );
        latestExecution.startStep(DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
        executionRepository.saveAndFlush(latestExecution);
        entityManager.clear();

        var found = executionRepository
                .findFirstByTypeOrderByIdDesc(DataPipelineType.ANNOUNCEMENT_REFINEMENT)
                .orElseThrow();

        assertThat(found.getExecutionId()).isEqualTo(latestExecution.getExecutionId());
        assertThat(found.getStatus()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(found.getCurrentStep())
                .isEqualTo(DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
        assertThat(found.getCompletedSteps())
                .containsExactly(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
        assertThat(found.getCompletedStepResults()).singleElement().satisfies(result -> {
            assertThat(result.getStep()).isEqualTo(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
            assertThat(result.getReport()).isEqualTo("{\"mappedSourceRowCount\":3}");
        });
    }

    @Test
    void 복구_직전_heartbeat나_상태가_바뀌면_실행을_실패로_덮지_않는다() {
        Instant startedAt = Instant.parse("2026-09-21T03:00:00Z");
        Instant now = startedAt.plusSeconds(300);
        UUID refreshedId = UUID.randomUUID();
        UUID completedId = UUID.randomUUID();
        executionStateService.create(refreshedId, DataPipelineType.COMPLEX_REFINEMENT, startedAt);
        executionStateService.startStep(refreshedId, DataPipelineStep.MAP_MYHOME_COMPLEXES);
        executionStateService.create(completedId, DataPipelineType.COMPLEX_REFINEMENT, startedAt);
        executionStateService.fail(completedId, null, "이미 실패한 실행", null, startedAt.plusSeconds(1));
        Long refreshedRowId = executionRepository.findByExecutionId(refreshedId).orElseThrow().getId();
        executionRepository.updateHeartbeat(refreshedRowId, now.minusSeconds(30));
        entityManager.clear();

        assertThat(executionStateService.recoverInterrupted(
                refreshedId, now.minusSeconds(120), now, "중단 복구"
        )).isFalse();
        assertThat(executionStateService.recoverInterrupted(
                completedId, now.minusSeconds(120), now, "중단 복구"
        )).isFalse();
        assertThat(executionRepository.findByExecutionId(refreshedId).orElseThrow())
                .satisfies(execution -> {
                    assertThat(execution.getStatus()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
                    assertThat(execution.getCurrentStep()).isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);
                });
        assertThat(executionRepository.findByExecutionId(completedId).orElseThrow().getFailureMessage())
                .isEqualTo("이미 실패한 실행");
    }

    @Test
    void 정기_실행_슬롯과_상위_실행을_조회한다() {
        UUID collectionId = UUID.randomUUID();
        Instant scheduledAt = Instant.parse("2026-09-21T03:00:00Z");
        DataPipelineExecution collection = DataPipelineExecution.start(
                collectionId,
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                scheduledAt,
                DataPipelineExecutionTrigger.SCHEDULED,
                scheduledAt,
                null
        );
        executionRepository.saveAndFlush(collection);
        DataPipelineExecution refinement = DataPipelineExecution.start(
                UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                scheduledAt.plusSeconds(1),
                DataPipelineExecutionTrigger.SCHEDULED,
                scheduledAt,
                collectionId
        );
        executionRepository.saveAndFlush(refinement);
        entityManager.clear();

        DataPipelineExecution foundCollection = executionRepository
                .findFirstByTypeAndScheduledAtOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_COLLECTION,
                        scheduledAt
                )
                .orElseThrow();
        DataPipelineExecution foundRefinement = executionRepository
                .findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
                        DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                        collectionId
                )
                .orElseThrow();

        assertThat(foundCollection.getExecutionTrigger())
                .isEqualTo(DataPipelineExecutionTrigger.SCHEDULED);
        assertThat(foundCollection.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(foundRefinement.getUpstreamExecutionId()).isEqualTo(collectionId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 단계마다_새_트랜잭션에서_실행을_갱신해도_완료_단계_순서가_충돌하지_않는다() {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-03T03:00:00Z");
        executionStateService.create(executionId, DataPipelineType.ANNOUNCEMENT_REFINEMENT, startedAt);
        executionStateService.startStep(executionId, DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
        executionStateService.completeStep(
                executionId,
                DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                "{\"mappedSourceRowCount\":1}"
        );
        executionStateService.startStep(executionId, DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
        executionStateService.completeStep(
                executionId,
                DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS,
                "{\"enrichedAnnouncementCount\":1}"
        );
        executionStateService.complete(executionId, startedAt.plusSeconds(10));

        DataPipelineExecution found = executionRepository.findByExecutionId(executionId).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(found.getCompletedSteps()).containsExactly(
                DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 건너뛴_단계와_완료한_단계를_각각_보존한다() {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-03T03:00:00Z");
        executionStateService.create(executionId, DataPipelineType.COMPLEX_REFINEMENT, startedAt);
        executionStateService.startStep(executionId, DataPipelineStep.MAP_MYHOME_COMPLEXES);
        executionStateService.skipStep(
                executionId,
                DataPipelineStep.MAP_MYHOME_COMPLEXES,
                "외부 API 호출 제한",
                "{\"rateLimitedSourceRowCount\":1}"
        );
        executionStateService.startStep(
                executionId,
                DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS
        );
        executionStateService.completeStep(
                executionId,
                DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS,
                "{}"
        );
        executionStateService.complete(executionId, startedAt.plusSeconds(10));

        DataPipelineExecution found = executionRepository.findByExecutionId(executionId).orElseThrow();

        assertThat(found.getStatus())
                .isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(found.getCompletedSteps())
                .containsExactly(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
        assertThat(found.getSkippedSteps()).singleElement().satisfies(skipped -> {
            assertThat(skipped.getStep()).isEqualTo(DataPipelineStep.MAP_MYHOME_COMPLEXES);
            assertThat(skipped.getReason()).isEqualTo("외부 API 호출 제한");
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 부분_실패한_단계별_보고서를_순서대로_보존한다() {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-03T03:00:00Z");
        executionStateService.create(executionId, DataPipelineType.ANNOUNCEMENT_COLLECTION, startedAt);
        executionStateService.startStep(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS
        );
        executionStateService.recordPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                "{\"failedPageCount\":1}"
        );
        executionStateService.startStepAfterPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG
        );
        executionStateService.recordPartialFailure(
                executionId,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                "{\"failedRequestCount\":2}"
        );

        DataPipelineExecution found = executionRepository.findByExecutionId(executionId).orElseThrow();

        assertThat(found.getPartiallyFailedSteps())
                .extracting(result -> result.getStep(), result -> result.getReport())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                                "{\"failedPageCount\":1}"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                                "{\"failedRequestCount\":2}"
                        )
                );
    }

    private DataPipelineExecution executionAt(String startedAt) {
        return DataPipelineExecution.start(
                UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                Instant.parse(startedAt)
        );
    }
}
