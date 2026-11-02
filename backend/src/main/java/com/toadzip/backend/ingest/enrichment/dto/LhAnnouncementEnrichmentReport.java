package com.toadzip.backend.ingest.enrichment.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

public record LhAnnouncementEnrichmentReport(
        int updatedAnnouncementCount,
        int unchangedAnnouncementCount,
        int createdScheduleCount,
        int updatedScheduleCount,
        int createdAttachmentCount,
        int updatedAttachmentCount,
        int updatedSupplyRowCount,
        int createdSupplyTargetCount,
        int updatedSupplyTargetCount,
        int failedSourceCount
) implements IngestStepReport {

    public static LhAnnouncementEnrichmentReport empty() {
        return new LhAnnouncementEnrichmentReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public LhAnnouncementEnrichmentReport plus(LhAnnouncementEnrichmentReport other) {
        return new LhAnnouncementEnrichmentReport(
                updatedAnnouncementCount + other.updatedAnnouncementCount,
                unchangedAnnouncementCount + other.unchangedAnnouncementCount,
                createdScheduleCount + other.createdScheduleCount,
                updatedScheduleCount + other.updatedScheduleCount,
                createdAttachmentCount + other.createdAttachmentCount,
                updatedAttachmentCount + other.updatedAttachmentCount,
                updatedSupplyRowCount + other.updatedSupplyRowCount,
                createdSupplyTargetCount + other.createdSupplyTargetCount,
                updatedSupplyTargetCount + other.updatedSupplyTargetCount,
                failedSourceCount + other.failedSourceCount
        );
    }

    public static LhAnnouncementEnrichmentReport failed() {
        return new LhAnnouncementEnrichmentReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
    }

    /** 보강하지 못한 원천은 단계를 멈추지 않는 경고다. */
    @Override
    public int stepFailureCount() {
        return 0;
    }

    @Override
    public int stepWarningCount() {
        return failedSourceCount;
    }
}
