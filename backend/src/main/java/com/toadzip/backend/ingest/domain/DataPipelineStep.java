package com.toadzip.backend.ingest.domain;

public enum DataPipelineStep {
    COLLECT_MYHOME_COMPLEXES(DataPipelineType.COMPLEX_COLLECTION, 1, "마이홈 단지 수집"),
    COLLECT_LH_LEASE_CATALOG(DataPipelineType.COMPLEX_COLLECTION, 2, "LH 임대 카탈로그 수집"),
    MAP_MYHOME_COMPLEXES(DataPipelineType.COMPLEX_REFINEMENT, 1, "마이홈 단지 정제"),
    ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS(
            DataPipelineType.COMPLEX_REFINEMENT,
            2,
            "LH 주택형 세대수 보강"
    ),
    COLLECT_MYHOME_ANNOUNCEMENTS(
            DataPipelineType.ANNOUNCEMENT_COLLECTION,
            1,
            "마이홈 공고 수집"
    ),
    COLLECT_LH_ANNOUNCEMENT_SUPPLIES(
            DataPipelineType.ANNOUNCEMENT_COLLECTION,
            2,
            "LH 공고 공급 원본 수집"
    ),
    COLLECT_LH_ANNOUNCEMENT_DETAILS(
            DataPipelineType.ANNOUNCEMENT_COLLECTION,
            3,
            "LH 공고 상세 원본 수집"
    ),
    MAP_MYHOME_ANNOUNCEMENTS(DataPipelineType.ANNOUNCEMENT_REFINEMENT, 1, "마이홈 공고 정제"),
    ENRICH_LH_ANNOUNCEMENTS(
            DataPipelineType.ANNOUNCEMENT_REFINEMENT,
            2,
            "LH 공고 상세·공급 정보 보강"
    );

    private final DataPipelineType type;

    private final int sequence;

    private final String displayName;

    DataPipelineStep(DataPipelineType type, int sequence, String displayName) {
        this.type = type;
        this.sequence = sequence;
        this.displayName = displayName;
    }

    public boolean belongsTo(DataPipelineType candidate) {
        return type == candidate;
    }

    public int sequence() {
        return sequence;
    }

    public String displayName() {
        return displayName;
    }
}
