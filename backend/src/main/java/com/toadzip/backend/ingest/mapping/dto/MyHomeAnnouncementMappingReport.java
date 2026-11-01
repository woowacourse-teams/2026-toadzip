package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

public record MyHomeAnnouncementMappingReport(
        int createdAnnouncementCount,
        int updatedAnnouncementCount,
        int unchangedAnnouncementCount,
        int createdSupplyRowCount,
        int updatedSupplyRowCount,
        int unchangedSupplyRowCount,
        int deletedSupplyRowCount,
        int failedSourceRowCount
) implements IngestStepReport {

    public MyHomeAnnouncementMappingReport {
        if (createdAnnouncementCount < 0
                || updatedAnnouncementCount < 0
                || unchangedAnnouncementCount < 0
                || createdSupplyRowCount < 0
                || updatedSupplyRowCount < 0
                || unchangedSupplyRowCount < 0
                || deletedSupplyRowCount < 0
                || failedSourceRowCount < 0) {
            throw new IllegalArgumentException("매핑 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static MyHomeAnnouncementMappingReport empty() {
        return new MyHomeAnnouncementMappingReport(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static MyHomeAnnouncementMappingReport failedRows(int count) {
        return new MyHomeAnnouncementMappingReport(0, 0, 0, 0, 0, 0, 0, count);
    }

    public MyHomeAnnouncementMappingReport plus(MyHomeAnnouncementMappingReport other) {
        return new MyHomeAnnouncementMappingReport(
                createdAnnouncementCount + other.createdAnnouncementCount,
                updatedAnnouncementCount + other.updatedAnnouncementCount,
                unchangedAnnouncementCount + other.unchangedAnnouncementCount,
                createdSupplyRowCount + other.createdSupplyRowCount,
                updatedSupplyRowCount + other.updatedSupplyRowCount,
                unchangedSupplyRowCount + other.unchangedSupplyRowCount,
                deletedSupplyRowCount + other.deletedSupplyRowCount,
                failedSourceRowCount + other.failedSourceRowCount
        );
    }

    /** 정제하지 못한 원천행은 단계를 멈추지 않는 경고다. */
    @Override
    public int stepFailureCount() {
        return 0;
    }

    @Override
    public int stepWarningCount() {
        return failedSourceRowCount;
    }
}
