package com.toadzip.backend.ingest.pipeline.domain;

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
public class DataPipelineCompletedStep {

    @Enumerated(EnumType.STRING)
    @Column(name = "completed_step", nullable = false, length = 60)
    private DataPipelineStep step;

    @Column(name = "completed_report", columnDefinition = "text")
    private String report;

    private DataPipelineCompletedStep(DataPipelineStep step, String report) {
        this.step = step;
        this.report = report;
    }

    public static DataPipelineCompletedStep of(DataPipelineStep step, String report) {
        if (step == null) {
            throw new IllegalArgumentException("완료한 단계는 필수입니다.");
        }
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException("완료한 단계의 실행 보고서는 필수입니다.");
        }
        return new DataPipelineCompletedStep(step, report);
    }
}
