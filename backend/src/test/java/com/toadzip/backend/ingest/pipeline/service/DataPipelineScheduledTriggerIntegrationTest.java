package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "ingest.scheduler.enabled=true",
        "ingest.scheduler.poll-interval-millis=200"
})
@ActiveProfiles("test")
@Import(DataPipelineScheduledTriggerIntegrationTest.RunnerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataPipelineScheduledTriggerIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T05:15:00Z");

    @Autowired
    private DataPipelineExecutionRepository executionRepository;

    @Test
    void 스케줄러가_자동으로_수집과_정제를_한_번씩_연결한다() throws InterruptedException {
        List<DataPipelineExecution> executions = List.of();
        for (int attempt = 0; attempt < 200; attempt++) {
            executions = executionRepository.findAll();
            if (executions.size() == 4 && executions.stream()
                    .allMatch(execution -> execution.getStatus() == DataPipelineExecutionStatus.COMPLETED)) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(executions).hasSize(4);
        assertThat(executions)
                .extracting(DataPipelineExecution::getType)
                .containsExactlyInAnyOrder(DataPipelineType.values());
        assertThat(executions)
                .extracting(DataPipelineExecution::getStatus)
                .containsOnly(DataPipelineExecutionStatus.COMPLETED);
        assertThat(executions)
                .extracting(DataPipelineExecution::getExecutionTrigger)
                .containsOnly(DataPipelineExecutionTrigger.SCHEDULED);
        assertThat(execution(executions, DataPipelineType.COMPLEX_COLLECTION).getScheduledAt())
                .isEqualTo(Instant.parse("2026-09-20T18:00:00Z"));
        assertThat(execution(executions, DataPipelineType.ANNOUNCEMENT_COLLECTION).getScheduledAt())
                .isEqualTo(Instant.parse("2026-09-21T03:00:00Z"));
        assertRefinementLinked(
                executions,
                DataPipelineType.COMPLEX_COLLECTION,
                DataPipelineType.COMPLEX_REFINEMENT
        );
        assertRefinementLinked(
                executions,
                DataPipelineType.ANNOUNCEMENT_COLLECTION,
                DataPipelineType.ANNOUNCEMENT_REFINEMENT
        );

        Thread.sleep(500);
        assertThat(executionRepository.findAll()).hasSize(4);
    }

    private void assertRefinementLinked(
            List<DataPipelineExecution> executions,
            DataPipelineType collectionType,
            DataPipelineType refinementType
    ) {
        DataPipelineExecution collection = execution(executions, collectionType);
        DataPipelineExecution refinement = execution(executions, refinementType);
        assertThat(refinement.getScheduledAt()).isEqualTo(collection.getScheduledAt());
        assertThat(refinement.getUpstreamExecutionId()).isEqualTo(collection.getExecutionId());
    }

    private DataPipelineExecution execution(List<DataPipelineExecution> executions, DataPipelineType type) {
        return executions.stream()
                .filter(execution -> execution.getType() == type)
                .findFirst()
                .orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RunnerConfiguration {

        @Bean
        @Primary
        Clock scheduledTriggerTestClock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        @Primary
        DataPipelineRunner scheduledTriggerTestRunner() {
            DataPipelineRunner runner = mock(DataPipelineRunner.class);
            doAnswer(invocation -> {
                DataPipelineType type = invocation.getArgument(0);
                DataPipelineProgressListener listener = invocation.getArgument(1);
                type.steps().forEach(step -> {
                    listener.started(step);
                    listener.completed(step, "{}");
                });
                return null;
            }).when(runner).run(any(), any());
            return runner;
        }
    }
}
