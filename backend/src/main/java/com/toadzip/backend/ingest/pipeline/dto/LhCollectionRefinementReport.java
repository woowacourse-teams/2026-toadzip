package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

/**
 * LH 공고 수집·정제 단계의 결과. 공급·상세 수집 수치와 묶음마다 이어 한 정제 수치를 함께 보존한다.
 *
 * @param allTargets 종료 정책 안의 공고를 모두 받은 실행인지. 증분 수집은 바뀐 공고와 다시 시도하는 공고만 받는다
 */
public record LhCollectionRefinementReport(
        IngestStepReport supply, IngestStepReport detail, IngestStepReport refinement, boolean allTargets
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

    /**
     * 공급·상세 수집이나 정제 중 하나라도 통째로 실패하면 이 단계는 성공한 몫이 없다. 증분 수집은 대상이 몇 건뿐이라 계속
     * 실패하는 공고 하나만 다시 시도해도 통째로 실패하므로, 실패가 한 건뿐이면 일부 실패로 둔다. 받다 실패한 공고는 다음 실행에서
     * 다시 대상이 되어 쌓이므로 기관 응답이 통째로 고장 나면 곧 두 건 넘게 실패해 통째 실패로 센다.
     */
    @Override
    public boolean stepFailedEntirely() {
        return failedEntirely(supply) || failedEntirely(detail) || failedEntirely(refinement);
    }

    private boolean failedEntirely(IngestStepReport part) {
        return part.stepFailedEntirely() && (allTargets || part.stepFailureCount() > 1);
    }
}
