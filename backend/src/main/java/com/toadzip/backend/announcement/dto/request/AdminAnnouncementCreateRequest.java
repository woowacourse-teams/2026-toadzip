package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;

public record AdminAnnouncementCreateRequest(
        @NotNull(message = "필수 값입니다.")
        Long housingComplexId,
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
        @NotNull(message = "필수 값입니다.")
        @Valid
        ReceptionPlaceRequest receptionPlace,
        @NotNull(message = "필수 값입니다.")
        @Valid
        SupplyRowRequest supplyRow
) {

    public record ReceptionPlaceRequest(
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String name,
            @NotNull(message = "필수 값입니다.")
            ReceptionMethod method,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String address,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String contact,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
            String url
    ) {
    }

    public record SupplyRowRequest(
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String sourceComplexName,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String sourceHousingTypeName,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String supplyPnu,
            YearMonth expectedMoveInMonth,
            @NotNull(message = "필수 값입니다.")
            SupplyCategory supplyCategory,
            @NotNull(message = "필수 값입니다.")
            @PositiveOrZero(message = "0 이상이어야 합니다.")
            Integer totalSupplyHouseholdCount
    ) {
    }
}
