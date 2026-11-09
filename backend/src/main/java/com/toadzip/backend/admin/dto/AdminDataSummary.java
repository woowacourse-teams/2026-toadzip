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

    /** 미확정 공고(releaseStatus UNCONFIRMED)는 모집유형·일정이 null일 수 있다. */
    public record AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
            LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate, String publicationType, String origin, String supplyOwner,
            String releaseStatus, String applicationStatus, List<String> attentionReasons) {
        public AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
                LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
                LocalDate winnerAnnouncementDate, String publicationType, String origin, String supplyOwner,
                String releaseStatus) {
            this(sourceIdentifier, originalUrl, recruitmentType, postedDate, applicationStartDate, applicationEndDate,
                    winnerAnnouncementDate, publicationType, origin, supplyOwner, releaseStatus, null, null);
        }
    }
}
