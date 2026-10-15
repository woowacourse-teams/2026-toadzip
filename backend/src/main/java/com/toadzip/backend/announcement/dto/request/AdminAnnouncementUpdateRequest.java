package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record AdminAnnouncementUpdateRequest(
        @NotNull(message = "필수 값입니다.")
        @PositiveOrZero Long version,
        @NotBlank(message = "필수 값입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        String name,
        @NotNull(message = "필수 값입니다.")
        RentalType rentalType,
        @NotNull(message = "필수 값입니다.")
        RecruitmentType recruitmentType,
        @NotNull(message = "필수 값입니다.")
        AgencyCode agencyCode,
        @NotNull(message = "필수 값입니다.")
        LocalDate postedDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate applicationStartDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate applicationEndDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate winnerAnnouncementDate,
        @NotBlank(message = "필수 값입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
        String originalUrl,
        @Valid ReceptionPlaceUpdateRequest receptionPlace,
        @Valid AdminDocumentEvidence documentEvidence
) {
    public AdminAnnouncementUpdateRequest(Long version, String name, RentalType rentalType,
            RecruitmentType recruitmentType, AgencyCode agencyCode, LocalDate postedDate,
            LocalDate applicationStartDate, LocalDate applicationEndDate, LocalDate winnerAnnouncementDate,
            String originalUrl, ReceptionPlaceUpdateRequest receptionPlace) {
        this(version, name, rentalType, recruitmentType, agencyCode, postedDate, applicationStartDate,
                applicationEndDate, winnerAnnouncementDate, originalUrl, receptionPlace, null);
    }

    public record ReceptionPlaceUpdateRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255) String name,
            @NotNull(message = "필수 값입니다.") ReceptionMethod method,
            @Size(max = 255) String address,
            @Size(max = 255) String contact,
            @Size(max = 255) @Pattern(regexp = "^https?://[^\\s]+$") String url
    ) { }

}
