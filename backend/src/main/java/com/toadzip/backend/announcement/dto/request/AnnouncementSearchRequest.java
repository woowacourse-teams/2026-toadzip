package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.domain.SearchScope;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate applicationTo,
        SearchScope scope,
        BigDecimal southWestLat,
        BigDecimal southWestLng,
        BigDecimal northEastLat,
        BigDecimal northEastLng
) {
    public AnnouncementSearchRequest(
            String keyword,
            String regionCode,
            List<RentalType> rentalTypes,
            List<ApplicationStatus> applicationStatuses,
            List<AnnouncementPublicationType> publicationTypes,
            List<AgencyCode> agencyCodes,
            List<RecruitmentType> recruitmentTypes,
            LocalDate applicationFrom,
            LocalDate applicationTo
    ) {
        this(keyword, regionCode, rentalTypes, applicationStatuses, publicationTypes, agencyCodes,
                recruitmentTypes, applicationFrom, applicationTo, null, null, null, null, null);
    }
}
