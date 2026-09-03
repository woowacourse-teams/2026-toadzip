package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class DataPipelineSkippedStep {

    @Enumerated(EnumType.STRING)
    @Column(name = "skipped_step", nullable = false, length = 60)
    private DataPipelineStep step;

    @Column(name = "skip_reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "skip_server_response", columnDefinition = "text")
    private String serverResponse;

    private DataPipelineSkippedStep(
            DataPipelineStep step,
            String reason,
            String serverResponse
    ) {
        this.step = step;
        this.reason = reason;
        this.serverResponse = serverResponse;
    }

    public static DataPipelineSkippedStep of(
            DataPipelineStep step,
            String reason,
            String serverResponse
    ) {
        if (step == null) {
            throw new IllegalArgumentException("건너뛴 단계는 필수입니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("건너뛴 이유는 필수입니다.");
        }
        return new DataPipelineSkippedStep(step, reason, serverResponse);
    }
}
