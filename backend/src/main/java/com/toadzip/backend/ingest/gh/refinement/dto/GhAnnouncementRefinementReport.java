package com.toadzip.backend.ingest.gh.refinement.dto;

/** GH 공고 정제 한 번의 결과. reviewRequired는 공개됐거나 관리자가 고친 공고라 원천 값을 덮지 않고 검토로 넘긴 수다. */
public record GhAnnouncementRefinementReport(
        int targetCount, int created, int updated, int unchanged, int reviewRequired, int trashed, int failed
) {
}
