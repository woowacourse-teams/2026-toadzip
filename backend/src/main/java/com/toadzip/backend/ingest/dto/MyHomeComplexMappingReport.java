package com.toadzip.backend.ingest.dto;

public record MyHomeComplexMappingReport(
        int createdComplexCount,
        int updatedComplexCount,
        int unchangedComplexCount,
        int createdHousingTypeCount,
        int updatedHousingTypeCount,
        int unchangedHousingTypeCount,
        int deletedHousingTypeCount,
        int failedSourceRowCount
) {

    public MyHomeComplexMappingReport {
        if (createdComplexCount < 0
                || updatedComplexCount < 0
                || unchangedComplexCount < 0
                || createdHousingTypeCount < 0
                || updatedHousingTypeCount < 0
                || unchangedHousingTypeCount < 0
                || deletedHousingTypeCount < 0
                || failedSourceRowCount < 0) {
            throw new IllegalArgumentException("매핑 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static MyHomeComplexMappingReport failedRows(int count) {
        return new MyHomeComplexMappingReport(0, 0, 0, 0, 0, 0, 0, count);
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
                failedSourceRowCount + other.failedSourceRowCount
        );
    }
}
