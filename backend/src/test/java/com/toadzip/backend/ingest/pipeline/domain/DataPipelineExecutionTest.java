package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataPipelineExecutionTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void 첫_단계보다_뒤의_단계를_먼저_시작할_수_없다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThatThrownBy(() -> execution.startStep(
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("다음 순서의 단계만 시작할 수 있습니다.");
    }

    @Test
    void 실행_중인_단계가_있으면_같거나_다른_단계를_다시_시작할_수_없다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        assertThatThrownBy(() -> execution.startStep(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실행 중인 단계를 완료하거나 건너뛴 뒤 다음 단계를 시작할 수 있습니다.");
        assertThatThrownBy(() -> execution.startStep(
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실행 중인 단계를 완료하거나 건너뛴 뒤 다음 단계를 시작할 수 있습니다.");
    }

    @Test
    void 완료한_단계를_다시_시작할_수_없다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
        execution.completeStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        assertThatThrownBy(() -> execution.startStep(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("다음 순서의 단계만 시작할 수 있습니다.");
    }

    @Test
    void 실행_중인_단계가_있으면_파이프라인을_완료할_수_없다() {
        DataPipelineExecution execution = execution(DataPipelineType.COMPLEX_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_COMPLEXES);

        assertThatThrownBy(() -> execution.complete(STARTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실행 중인 단계를 완료하거나 건너뛴 뒤 파이프라인을 완료할 수 있습니다.");
    }

    @Test
    void 부분_실패한_단계의_바로_다음_단계를_시작할_수_있다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        execution.startStepAfterPartialFailure(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
        );

        assertThat(execution.getCurrentStep())
                .isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);
    }

    @Test
    void 부분_실패_후_완료한_단계의_다음_단계를_시작할_수_있다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);
        execution.startStepAfterPartialFailure(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
        );
        execution.completeStep(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES);

        execution.startStep(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);

        assertThat(execution.getCurrentStep())
                .isEqualTo(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS);
    }

    @Test
    void 부분_실패한_단계를_건너뛰어_다음다음_단계를_시작할_수_없다() {
        DataPipelineExecution execution = execution(DataPipelineType.ANNOUNCEMENT_COLLECTION);
        execution.startStep(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS);

        assertThatThrownBy(() -> execution.startStepAfterPartialFailure(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("부분 실패한 단계의 바로 다음 단계만 시작할 수 있습니다.");
    }

    private DataPipelineExecution execution(DataPipelineType type) {
        return DataPipelineExecution.start(UUID.randomUUID(), type, STARTED_AT);
    }
}
