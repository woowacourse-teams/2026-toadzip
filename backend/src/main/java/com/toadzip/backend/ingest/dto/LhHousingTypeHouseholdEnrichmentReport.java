package com.toadzip.backend.ingest.dto;

public record LhHousingTypeHouseholdEnrichmentReport(
        int sourceComplexCount,
        int matchedComplexCount,
        int updatedHousingTypeCount,
        int unchangedHousingTypeCount,
        int failedSourceComplexCount,
        int unmatchedHousingTypeCount
) {

    public LhHousingTypeHouseholdEnrichmentReport {
        if (sourceComplexCount < 0
                || matchedComplexCount < 0
                || updatedHousingTypeCount < 0
                || unchangedHousingTypeCount < 0
                || failedSourceComplexCount < 0
                || unmatchedHousingTypeCount < 0) {
            throw new IllegalArgumentException(
                    "LH 주택형 세대수 보강 결과 개수는 음수일 수 없습니다."
            );
        }
    }

    public static LhHousingTypeHouseholdEnrichmentReport empty(int sourceComplexCount) {
        return new LhHousingTypeHouseholdEnrichmentReport(sourceComplexCount, 0, 0, 0, 0, 0);
    }

    public static LhHousingTypeHouseholdEnrichmentReport failed() {
        return new LhHousingTypeHouseholdEnrichmentReport(0, 0, 0, 0, 1, 0);
    }

    public static LhHousingTypeHouseholdEnrichmentReport matched(
            int updatedHousingTypeCount,
            int unchangedHousingTypeCount,
            int unmatchedHousingTypeCount
    ) {
        return new LhHousingTypeHouseholdEnrichmentReport(
                0, 1, updatedHousingTypeCount, unchangedHousingTypeCount, 0, unmatchedHousingTypeCount
        );
    }

    public LhHousingTypeHouseholdEnrichmentReport plus(
            LhHousingTypeHouseholdEnrichmentReport other
    ) {
        return new LhHousingTypeHouseholdEnrichmentReport(
                sourceComplexCount + other.sourceComplexCount,
                matchedComplexCount + other.matchedComplexCount,
                updatedHousingTypeCount + other.updatedHousingTypeCount,
                unchangedHousingTypeCount + other.unchangedHousingTypeCount,
                failedSourceComplexCount + other.failedSourceComplexCount,
                unmatchedHousingTypeCount + other.unmatchedHousingTypeCount
        );
    }
}
