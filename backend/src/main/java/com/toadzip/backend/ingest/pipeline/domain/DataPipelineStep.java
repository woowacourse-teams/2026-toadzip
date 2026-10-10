package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineStep {
    COLLECT_SH_ANNOUNCEMENTS("SH 공고 원천 수집"),
    COLLECT_MYHOME_COMPLEXES("마이홈 단지 수집"),
    COLLECT_LH_LEASE_CATALOG("LH 임대 카탈로그 수집"),
    MAP_MYHOME_COMPLEXES("마이홈 단지 정제"),
    ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS("LH 주택형 세대수 보강"),
    COLLECT_MYHOME_ANNOUNCEMENTS("마이홈 공고 수집"),
    COLLECT_LH_ANNOUNCEMENT_CATALOG("LH 공고 목록 수집"),
    COLLECT_LH_ANNOUNCEMENT_SUPPLIES("LH 공고 공급 원본 수집"),
    COLLECT_LH_ANNOUNCEMENT_DETAILS("LH 공고 상세 원본 수집"),
    MAP_MYHOME_ANNOUNCEMENTS("마이홈 공고 정제"),
    ENRICH_LH_ANNOUNCEMENTS("LH 공고 상세·공급 정보 보강");

    private final String displayName;

    DataPipelineStep(String displayName) {
        this.displayName = displayName;
    }

    public boolean isCollection() {
        return switch (this) {
            case COLLECT_SH_ANNOUNCEMENTS, COLLECT_MYHOME_COMPLEXES, COLLECT_LH_LEASE_CATALOG,
                    COLLECT_MYHOME_ANNOUNCEMENTS, COLLECT_LH_ANNOUNCEMENT_CATALOG,
                    COLLECT_LH_ANNOUNCEMENT_SUPPLIES, COLLECT_LH_ANNOUNCEMENT_DETAILS -> true;
            default -> false;
        };
    }

    public String displayName() {
        return displayName;
    }
}
