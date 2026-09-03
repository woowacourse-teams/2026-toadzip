package com.toadzip.backend.ingest.dto;

public record MyHomeComplexMappingReport(
        int createdComplexCount,
        int updatedComplexCount,
        int unchangedComplexCount,
        int createdHousingTypeCount,
        int updatedHousingTypeCount,
        int unchangedHousingTypeCount,
        int deletedHousingTypeCount,
        int failedSourceRowCount,
        int rateLimitedSourceRowCount
) {

    public MyHomeComplexMappingReport(
            int createdComplexCount,
            int updatedComplexCount,
            int unchangedComplexCount,
            int createdHousingTypeCount,
            int updatedHousingTypeCount,
            int unchangedHousingTypeCount,
            int deletedHousingTypeCount,
            int failedSourceRowCount
    ) {
        this(
                createdComplexCount,
                updatedComplexCount,
                unchangedComplexCount,
                createdHousingTypeCount,
                updatedHousingTypeCount,
                unchangedHousingTypeCount,
                deletedHousingTypeCount,
                failedSourceRowCount,
                0
        );
    }

    public MyHomeComplexMappingReport {
        if (createdComplexCount < 0
                || updatedComplexCount < 0
                || unchangedComplexCount < 0
                || createdHousingTypeCount < 0
                || updatedHousingTypeCount < 0
                || unchangedHousingTypeCount < 0
                || deletedHousingTypeCount < 0
                || failedSourceRowCount < 0
                || rateLimitedSourceRowCount < 0
                || rateLimitedSourceRowCount > failedSourceRowCount) {
            throw new IllegalArgumentException("매핑 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static MyHomeComplexMappingReport failedRows(int count) {
        return new MyHomeComplexMappingReport(0, 0, 0, 0, 0, 0, 0, count, 0);
    }

    public static MyHomeComplexMappingReport rateLimitedRows(int count) {
        return new MyHomeComplexMappingReport(0, 0, 0, 0, 0, 0, 0, count, count);
    }

    public MyHomeComplexMappingReport plus(MyHomeComplexMappingReport other) {
        return new MyHomeComplexMappingReport(
                createdComplexCount + other.createdComplexCount,
                updatedComplexCount + other.updatedComplexCount,
                unchangedComplexCount + other.unchangedComplexCount,
                createdHousingTypeCount + other.createdHousingTypeCount,
                updatedHousingTypeCount + other.updatedHousingTypeCount,
                unchangedHousingTypeCount + other.unchangedHousingTypeCount,
                deletedHousingTypeCount + other.deletedHousingTypeCount,
                failedSourceRowCount + other.failedSourceRowCount,
                rateLimitedSourceRowCount + other.rateLimitedSourceRowCount
        );
    }
}
