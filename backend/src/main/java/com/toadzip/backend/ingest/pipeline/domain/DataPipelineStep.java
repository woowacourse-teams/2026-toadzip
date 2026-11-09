package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineStep {
    // 지원을 종료한 SH·GH 공고 수집·정제 두 단계다. 지난 실행 이력을 읽도록 남긴다.
    COLLECT_SH_ANNOUNCEMENTS("SH 공고 원천 수집", Kind.COLLECTION),
    COLLECT_GH_ANNOUNCEMENTS("GH 공고 원천 수집", Kind.COLLECTION),
    COLLECT_MYHOME_COMPLEXES("마이홈 단지 수집", Kind.COLLECTION),
    COLLECT_LH_LEASE_CATALOG("LH 임대 카탈로그 수집", Kind.COLLECTION),
    MAP_MYHOME_COMPLEXES("마이홈 단지 정제", Kind.REFINEMENT),
    ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS("LH 주택형 세대수 보강", Kind.REFINEMENT),
    COLLECT_MYHOME_ANNOUNCEMENTS("마이홈 공고 수집", Kind.COLLECTION),
    COLLECT_LH_ANNOUNCEMENT_CATALOG("LH 공고 목록 수집", Kind.LH_ANNOUNCEMENT_COLLECTION),
    COLLECT_LH_ANNOUNCEMENT_SUPPLIES("LH 공고 공급 원본 수집", Kind.LH_ANNOUNCEMENT_COLLECTION),
    COLLECT_LH_ANNOUNCEMENT_DETAILS("LH 공고 상세 원본 수집", Kind.LH_ANNOUNCEMENT_COLLECTION),
    MAP_MYHOME_ANNOUNCEMENTS("마이홈 공고 정제", Kind.REFINEMENT),
    ENRICH_LH_ANNOUNCEMENTS("LH 공고 상세·공급 정보 보강", Kind.REFINEMENT),
    MAP_SH_ANNOUNCEMENTS("SH 공고 정제", Kind.REFINEMENT),
    MAP_GH_ANNOUNCEMENTS("GH 공고 정제", Kind.REFINEMENT),
    MAP_LH_ANNOUNCEMENTS("LH 공고 정제", Kind.REFINEMENT),
    COLLECT_REFINE_SH_ANNOUNCEMENTS("SH 공고 수집·정제", Kind.COLLECTION),
    COLLECT_REFINE_GH_ANNOUNCEMENTS("GH 공고 수집·정제", Kind.COLLECTION),
    COLLECT_REFINE_LH_ANNOUNCEMENTS("LH 공고 수집·정제", Kind.LH_ANNOUNCEMENT_COLLECTION);

    private final String displayName;
    private final Kind kind;

    DataPipelineStep(String displayName, Kind kind) {
        this.displayName = displayName;
        this.kind = kind;
    }

    public boolean isCollection() {
        return kind != Kind.REFINEMENT;
    }

    /** LH 공고 API를 함께 쓰는 수집 단계라서, 한 단계가 호출 제한에 걸리면 뒤 단계도 건너뛴다. */
    public boolean isLhAnnouncementCollection() {
        return kind == Kind.LH_ANNOUNCEMENT_COLLECTION;
    }

    /** 수집한 묶음(게시판 한 페이지, LH 요청 묶음)을 받을 때마다 바로 정제하는 단계다. 호출 제한에 걸려도 처리한 묶음은 정제돼 있다. */
    public boolean isCollectionAndRefinement() {
        return this == COLLECT_REFINE_SH_ANNOUNCEMENTS || this == COLLECT_REFINE_GH_ANNOUNCEMENTS
                || this == COLLECT_REFINE_LH_ANNOUNCEMENTS;
    }

    public String displayName() {
        return displayName;
    }

    private enum Kind {
        COLLECTION,
        LH_ANNOUNCEMENT_COLLECTION,
        REFINEMENT
    }
}
