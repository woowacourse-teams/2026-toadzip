package com.toadzip.backend.ingest.gh.refinement.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;
import com.toadzip.backend.ingest.domain.SourceRefinementFailure;
import java.util.List;

/**
 * GH 공고 정제 한 번의 결과. reviewRequired는 공개됐거나 관리자가 고친 공고라 원천 값을 덮지 않고 검토로 넘긴 수, supplyRecheckRequired는
 * 관리자가 맡은 공고의 원천 공급이 바뀌어 공급 재확인을 새로 켠 수다.
 */
public record GhAnnouncementRefinementReport(
        int targetCount, int created, int updated, int unchanged, int reviewRequired, int trashed,
        int supplyRecheckRequired, int failed, List<SourceRefinementFailure> failures
) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return failed;
    }
}
