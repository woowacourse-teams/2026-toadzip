package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

/** LH 공고 수집·정제 단계의 결과. 공급·상세 수집 수치와 묶음마다 이어 한 정제 수치를 함께 보존한다. */
public record LhCollectionRefinementReport(
        IngestStepReport supply, IngestStepReport detail, IngestStepReport refinement
) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return supply.stepFailureCount() + detail.stepFailureCount() + refinement.stepFailureCount();
    }

    @Override
    public int stepRateLimitedFailureCount() {
        return supply.stepRateLimitedFailureCount() + detail.stepRateLimitedFailureCount();
    }

    @Override
    public int stepWarningCount() {
        return supply.stepWarningCount() + detail.stepWarningCount() + refinement.stepWarningCount();
    }

    /** 공급·상세 수집이나 정제 중 하나라도 통째로 실패하면 이 단계는 성공한 몫이 없다. */
    @Override
    public boolean stepFailedEntirely() {
        return supply.stepFailedEntirely() || detail.stepFailedEntirely() || refinement.stepFailedEntirely();
    }
}
