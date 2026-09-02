package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DataPipelineExecutionRepositoryTest {

    @Autowired
    private DataPipelineExecutionRepository executionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 다른_영속성_컨텍스트에서도_최신_실행의_단계와_상태를_조회한다() {
        DataPipelineExecution olderExecution = executionAt("2026-09-03T02:00:00Z");
        executionRepository.saveAndFlush(olderExecution);
        DataPipelineExecution latestExecution = executionAt("2026-09-03T02:00:00Z");
        latestExecution.startStep(DataPipelineStep.MAP_MYHOME_COMPLEXES);
        latestExecution.completeStep(DataPipelineStep.MAP_MYHOME_COMPLEXES);
        latestExecution.startStep(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
        executionRepository.saveAndFlush(latestExecution);
        entityManager.clear();

        var found = executionRepository
                .findFirstByTypeOrderByStartedAtDescIdDesc(DataPipelineType.REFINEMENT)
                .orElseThrow();

        assertThat(found.getExecutionId()).isEqualTo(latestExecution.getExecutionId());
        assertThat(found.getStatus()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(found.getCurrentStep())
                .isEqualTo(DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS);
        assertThat(found.getCompletedSteps())
                .containsExactly(DataPipelineStep.MAP_MYHOME_COMPLEXES);
    }

    private DataPipelineExecution executionAt(String startedAt) {
        return DataPipelineExecution.start(
                UUID.randomUUID(),
                DataPipelineType.REFINEMENT,
                Instant.parse(startedAt)
        );
    }
}
