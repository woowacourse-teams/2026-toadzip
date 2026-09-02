package com.toadzip.backend.ingest.domain;

public enum DataPipelineStep {
    COLLECT_MYHOME_COMPLEXES(DataPipelineType.COLLECTION, 1, "마이홈 단지 수집"),
    COLLECT_LH_LEASE_CATALOG(DataPipelineType.COLLECTION, 2, "LH 임대 카탈로그 수집"),
    COLLECT_MYHOME_ANNOUNCEMENTS(DataPipelineType.COLLECTION, 3, "마이홈 공고 수집"),
    COLLECT_LH_ANNOUNCEMENT_SUPPLIES(DataPipelineType.COLLECTION, 4, "LH 공고 공급 원본 수집"),
    COLLECT_LH_ANNOUNCEMENT_DETAILS(DataPipelineType.COLLECTION, 5, "LH 공고 상세 원본 수집"),
    MAP_MYHOME_COMPLEXES(DataPipelineType.REFINEMENT, 1, "마이홈 단지 정제"),
    ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS(DataPipelineType.REFINEMENT, 2, "LH 주택형 세대수 보강"),
    MAP_MYHOME_ANNOUNCEMENTS(DataPipelineType.REFINEMENT, 3, "마이홈 공고 정제"),
    ENRICH_LH_ANNOUNCEMENTS(DataPipelineType.REFINEMENT, 4, "LH 공고 상세·공급 정보 보강");

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
