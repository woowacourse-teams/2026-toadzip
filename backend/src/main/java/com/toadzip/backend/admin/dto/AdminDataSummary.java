package com.toadzip.backend.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminDataSummary(long id, String name, String subtitle, String provider, String rental,
        boolean deleted, boolean modified, boolean reviewRequired, Instant updatedAt,
        ComplexSummary complex, AnnouncementSummary announcement) {
    public record ComplexSummary(String sourceIdentifier, LocalDate completionDate, Integer totalHouseholdCount,
            Integer totalParkingCount, String heatingType, String buildingType, String corridorType,
            Boolean hasElevator, Integer moveOutCountLastYear, String verificationStatus, int reviewedFieldCount) { }

    /**
     * 미확정 공고(releaseStatus UNCONFIRMED)는 모집유형·일정이 null일 수 있다. supplyConfirmed는 공고 공급표(정본)를 확정했는지다.
     * supplyOwner는 예전 화면이 읽는 값으로 확정했으면 ANNOUNCEMENT_DOCUMENT, 아니면 LH_SOURCE다. processingStage는 공고문 PDF
     * 처리 단계이며 공고 관리 목록에서만 채운다.
     */
    public record AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
            LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate, String publicationType, String origin, String supplyOwner,
            String releaseStatus, String applicationStatus, List<String> attentionReasons, boolean supplyConfirmed,
            String processingStage) {
        public AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
                LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
                LocalDate winnerAnnouncementDate, String publicationType, String origin, boolean supplyConfirmed,
                String releaseStatus, String applicationStatus, List<String> attentionReasons, String processingStage) {
            this(sourceIdentifier, originalUrl, recruitmentType, postedDate, applicationStartDate, applicationEndDate,
                    winnerAnnouncementDate, publicationType, origin,
                    supplyConfirmed ? "ANNOUNCEMENT_DOCUMENT" : "LH_SOURCE", releaseStatus, applicationStatus,
                    attentionReasons, supplyConfirmed, processingStage);
        }

        public AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
                LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
                LocalDate winnerAnnouncementDate, String publicationType, String origin, boolean supplyConfirmed,
                String releaseStatus) {
            this(sourceIdentifier, originalUrl, recruitmentType, postedDate, applicationStartDate, applicationEndDate,
                    winnerAnnouncementDate, publicationType, origin, supplyConfirmed, releaseStatus, null, null, null);
        }
    }
}
