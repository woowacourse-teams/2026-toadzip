package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "data_pipeline_executions",
        indexes = @Index(
                name = "idx_data_pipeline_execution_type_id",
                columnList = "type, id"
        )
)
@NoArgsConstructor(access = PROTECTED)
public class DataPipelineExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataPipelineType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataPipelineExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 60)
    private DataPipelineStep currentStep;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "data_pipeline_execution_completed_steps",
            joinColumns = @JoinColumn(name = "data_pipeline_execution_id")
    )
    @OrderColumn(name = "step_order")
    @Enumerated(EnumType.STRING)
    @Column(name = "completed_step", nullable = false, length = 60)
    private List<DataPipelineStep> completedSteps = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 60)
    private DataPipelineStep failedStep;

    @Column(length = 500)
    private String failureMessage;

    @Column(columnDefinition = "text")
    private String failureServerResponse;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant heartbeatAt;

    private Instant finishedAt;

    private DataPipelineExecution(UUID executionId, DataPipelineType type, Instant startedAt) {
        this.executionId = executionId;
        this.type = type;
        this.status = DataPipelineExecutionStatus.RUNNING;
        this.startedAt = startedAt;
        this.heartbeatAt = startedAt;
    }

    public static DataPipelineExecution start(
            UUID executionId,
            DataPipelineType type,
            Instant startedAt
    ) {
        return new DataPipelineExecution(executionId, type, startedAt);
    }

    public void startStep(DataPipelineStep step) {
        requireRunning();
        if (!step.belongsTo(type)) {
            throw new IllegalStateException("실행 유형에 속하지 않는 단계입니다.");
        }
        currentStep = step;
    }

    public void completeStep(DataPipelineStep step) {
        requireRunning();
        if (currentStep != step) {
            throw new IllegalStateException("현재 실행 중인 단계만 완료할 수 있습니다.");
        }
        completedSteps.add(step);
        currentStep = null;
    }

    public void complete(Instant completedAt) {
        requireRunning();
        if (completedSteps.size() != type.steps().size()) {
            throw new IllegalStateException(
                    "모든 단계를 완료한 뒤 파이프라인을 완료할 수 있습니다."
            );
        }
        status = DataPipelineExecutionStatus.COMPLETED;
        currentStep = null;
        finishedAt = completedAt;
    }

    public void fail(
            DataPipelineStep failedStep,
            String message,
            String serverResponse,
            Instant failedAt
    ) {
        requireRunning();
        status = DataPipelineExecutionStatus.FAILED;
        currentStep = failedStep;
        this.failedStep = failedStep;
        failureMessage = message;
        failureServerResponse = serverResponse;
        finishedAt = failedAt;
    }

    public boolean isRunning() {
        return status == DataPipelineExecutionStatus.RUNNING;
    }

    public boolean isCompleted() {
        return status == DataPipelineExecutionStatus.COMPLETED;
    }

    public void failCompletionPersistence(String message, Instant failedAt) {
        if (!isCompleted()) {
            throw new IllegalStateException(
                    "완료된 파이프라인만 완료 저장 실패로 전환할 수 있습니다."
            );
        }
        status = DataPipelineExecutionStatus.FAILED;
        failureMessage = message;
        finishedAt = failedAt;
    }

    private void requireRunning() {
        if (status != DataPipelineExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "실행 중인 데이터 파이프라인만 상태를 변경할 수 있습니다."
            );
        }
    }
}
