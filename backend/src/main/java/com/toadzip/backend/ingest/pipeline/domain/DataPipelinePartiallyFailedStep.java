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
public class DataPipelinePartiallyFailedStep {

    @Enumerated(EnumType.STRING)
    @Column(name = "partially_failed_step", nullable = false, length = 60)
    private DataPipelineStep step;

    @Column(name = "partial_failure_report", nullable = false, columnDefinition = "text")
    private String report;

    private DataPipelinePartiallyFailedStep(DataPipelineStep step, String report) {
        this.step = step;
        this.report = report;
    }

    public static DataPipelinePartiallyFailedStep of(DataPipelineStep step, String report) {
        if (step == null) {
            throw new IllegalArgumentException("부분 실패한 단계는 필수입니다.");
        }
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException("부분 실패한 단계의 실행 보고서는 필수입니다.");
        }
        return new DataPipelinePartiallyFailedStep(step, report);
    }
}
