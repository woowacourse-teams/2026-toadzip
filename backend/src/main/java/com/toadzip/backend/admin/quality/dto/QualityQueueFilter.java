package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.AgencyCode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QualityQueueFilter(QualityResource resource,
        @Pattern(regexp = "|MISSING|SOURCE_MISMATCH|STALE|UNREVIEWED|ON_HOLD|SOURCE_CHANGED") String reason,
        @Size(max = 200) String keyword,
        @Size(max = 500) String sourceIdentifier,
        @Positive Long announcementId, @Positive Long complexId,
        AgencyCode provider, ApplicationStatus applicationStatus) {
    public QualityQueueFilter {
        if (reason == null) { reason = ""; }
        if (keyword == null) { keyword = ""; }
        if (sourceIdentifier == null) { sourceIdentifier = ""; }
        keyword = keyword.strip();
        sourceIdentifier = sourceIdentifier.strip();
    }
}
