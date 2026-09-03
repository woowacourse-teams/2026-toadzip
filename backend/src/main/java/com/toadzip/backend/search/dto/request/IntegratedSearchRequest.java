package com.toadzip.backend.search.dto.request;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.RentalType;
import java.util.List;

public record IntegratedSearchRequest(
        String query,
        Boolean preview,
        Integer page,
        Integer size,
        List<RentalType> rentalTypes,
        List<ApplicationStatus> applicationStatuses,
        Boolean hasActiveAnnouncement
) {
}
