package com.toadzip.backend.search.repository;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.search.domain.SearchMatch;
import java.time.LocalDate;
import java.util.Set;

public record IntegratedSearchCondition(
        SearchMatch match,
        Set<RentalType> rentalTypes,
        Set<ApplicationStatus> applicationStatuses,
        Boolean hasActiveAnnouncement,
        LocalDate today
) {
    public IntegratedSearchCondition {
        rentalTypes = Set.copyOf(rentalTypes);
        applicationStatuses = Set.copyOf(applicationStatuses);
    }
}
