package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.service.DataPipelineExecutionStateService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
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
        latestExecution.completeStep(DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
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
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 단계마다_새_트랜잭션에서_실행을_갱신해도_완료_단계_순서가_충돌하지_않는다() {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-03T03:00:00Z");
        executionStateService.create(executionId, DataPipelineType.ANNOUNCEMENT_REFINEMENT, startedAt);
        executionStateService.startStep(executionId, DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
        executionStateService.completeStep(executionId, DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS);
        executionStateService.startStep(executionId, DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
        executionStateService.completeStep(executionId, DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS);
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
                DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS
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

    private DataPipelineExecution executionAt(String startedAt) {
        return DataPipelineExecution.start(
                UUID.randomUUID(),
                DataPipelineType.ANNOUNCEMENT_REFINEMENT,
                Instant.parse(startedAt)
        );
    }
}
