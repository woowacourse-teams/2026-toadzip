package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record AnnouncementSearchRequest(
        String keyword,
        String regionCode,
        List<@NotNull RentalType> rentalTypes,
        List<@NotNull ApplicationStatus> applicationStatuses,
        List<@NotNull AnnouncementPublicationType> publicationTypes,
        List<@NotNull AgencyCode> agencyCodes,
        List<@NotNull RecruitmentType> recruitmentTypes,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate applicationFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate applicationTo
) {
}
