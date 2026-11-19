package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

/** 기관 게시판 수집·정제 단계의 결과. 수집 수치와 페이지마다 이어 한 정제 수치를 함께 보존한다. */
public record BoardCollectionRefinementReport(
        IngestStepReport collection, IngestStepReport refinement
) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return collection.stepFailureCount() + refinement.stepFailureCount();
    }

    @Override
    public int stepRateLimitedFailureCount() {
        return collection.stepRateLimitedFailureCount();
    }

    @Override
    public int stepWarningCount() {
        return collection.stepWarningCount() + refinement.stepWarningCount();
    }

    /** 수집이나 정제 중 하나라도 통째로 실패하면 이 단계는 성공한 몫이 없다. */
    @Override
    public boolean stepFailedEntirely() {
        return collection.stepFailedEntirely() || refinement.stepFailedEntirely();
    }
}
