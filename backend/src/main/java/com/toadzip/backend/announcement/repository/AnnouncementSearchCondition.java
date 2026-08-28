package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import java.util.Set;

public record AnnouncementSearchCondition(
        String keyword,
        Set<String> regionCodes,
        Set<RentalType> rentalTypes,
        Set<ApplicationStatus> applicationStatuses,
        Set<AnnouncementPublicationType> publicationTypes,
        Set<AgencyCode> agencyCodes,
        Set<RecruitmentType> recruitmentTypes,
        LocalDate applicationFrom,
        LocalDate applicationTo,
        LocalDate today
) {
}
