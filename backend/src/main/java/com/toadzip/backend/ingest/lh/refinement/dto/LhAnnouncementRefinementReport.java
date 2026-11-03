package com.toadzip.backend.ingest.lh.refinement.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;
import java.util.Map;

/**
 * LH 공고 정제 한 번의 결과. 공고 수는 대상(최신 공고목록의 임대 공고) 기준이고, 보류는 공고를 만들거나 갱신하지 않은 이유별 수다.
 * 실패한 공고는 그 공고만 되돌리고 다음 공고를 계속 정제한다.
 */
public record LhAnnouncementRefinementReport(
        int targetCount,
        int createdAnnouncementCount,
        int adoptedAnnouncementCount,
        int updatedAnnouncementCount,
        Map<String, Integer> heldAnnouncementCounts,
        int failedAnnouncementCount,
        int documentOwnedAnnouncementCount,
        int recheckRequiredAnnouncementCount,
        int linkedRevisionCount,
        int unlinkedRevisionCount,
        ComplexCounts announcementComplexes,
        SupplyCounts supplyRows
) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return failedAnnouncementCount;
    }

    public record ComplexCounts(int created, int updated, int absent, int migrated, int inherited, int auto,
            int unlinked) {
    }

    public record SupplyCounts(int created, int updated, int deleted, int createdTargets, int updatedTargets,
            int deletedTargets, int filledAnnouncementComplexes, Map<String, Integer> matchStatuses) {
    }
}
