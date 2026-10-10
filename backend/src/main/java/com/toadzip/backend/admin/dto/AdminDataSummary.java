package com.toadzip.backend.admin.dto;

import java.time.Instant;
import java.time.LocalDate;

public record AdminDataSummary(long id, String name, String subtitle, String provider, String rental,
        boolean deleted, boolean modified, boolean reviewRequired, Instant updatedAt,
        ComplexSummary complex, AnnouncementSummary announcement) {
    public record ComplexSummary(String sourceIdentifier, LocalDate completionDate, int totalHouseholdCount,
            int totalParkingCount, String heatingType, String buildingType, String corridorType,
            Boolean hasElevator, Integer moveOutCountLastYear, String verificationStatus, int reviewedFieldCount) { }

    public record AnnouncementSummary(String sourceIdentifier, String originalUrl, String recruitmentType,
            LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate) { }
}
