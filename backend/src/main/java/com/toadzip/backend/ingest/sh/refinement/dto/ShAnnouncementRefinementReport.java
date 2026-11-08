package com.toadzip.backend.ingest.sh.refinement.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;
import com.toadzip.backend.ingest.domain.SourceRefinementFailure;
import java.util.List;

/** SH 공고 정제 한 번의 결과. reviewRequired는 공개됐거나 관리자가 고친 공고라 원천 값을 덮지 않고 검토로 넘긴 수, revisionsLinked는 정정공고를 원공고에 이은 수, revisionsUnlinked는 원공고를 하나로 정하지 못해 잇지 않은 정정공고 수다. */
public record ShAnnouncementRefinementReport(
        int targetCount, int created, int updated, int unchanged, int reviewRequired, int trashed, int revisionsLinked,
        int revisionsUnlinked, int failed,
        List<SourceRefinementFailure> failures
) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return failed;
    }
}
