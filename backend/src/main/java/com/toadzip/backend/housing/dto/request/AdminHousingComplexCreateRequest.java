package com.toadzip.backend.housing.dto.request;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminHousingComplexCreateRequest(
        @NotBlank(message = "필수 값입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        String name,
        @NotNull(message = "필수 값입니다.")
        RentalType rentalType,
        @NotNull(message = "필수 값입니다.")
        AgencyCode agencyCode,
        @NotNull(message = "필수 값입니다.")
        @Valid
        AddressRequest address,
        @NotNull(message = "필수 값입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer totalHouseholdCount,
        @NotNull(message = "필수 값입니다.")
        LocalDate completionDate,
        @NotNull(message = "필수 값입니다.")
        HeatingType heatingType,
        @NotNull(message = "필수 값입니다.")
        BuildingType buildingType,
        @NotNull(message = "필수 값입니다.")
        CorridorType corridorType,
        @NotNull(message = "필수 값입니다.")
        Boolean hasElevator,
        @NotNull(message = "필수 값입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer totalParkingCount,
        @Size(max = 255, message = "255자 이하여야 합니다.")
        @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
        String overviewImageUrl,
        @NotNull(message = "필수 값입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer moveOutCountLastYear
) {

    public enum HeatingType {
        INDIVIDUAL,
        CENTRAL,
        DISTRICT,
        ETC
    }

    public enum BuildingType {
        APARTMENT,
        OFFICETEL,
        ETC
    }

    public enum CorridorType {
        STAIR,
        CORRIDOR,
        MIXED,
        UNKNOWN
    }

    public record AddressRequest(
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String roadAddress,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String pnu,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String legalDongCode,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String provinceCode,
            @NotBlank(message = "필수 값입니다.")
            @Size(max = 255, message = "255자 이하여야 합니다.")
            String cityCountyDistrictCode,
            @NotNull(message = "필수 값입니다.")
            @DecimalMin(value = "-90", message = "-90 이상이어야 합니다.")
            @DecimalMax(value = "90", message = "90 이하여야 합니다.")
            @Digits(integer = 3, fraction = 6, message = "소수 여섯 자리 이하여야 합니다.")
            BigDecimal latitude,
            @NotNull(message = "필수 값입니다.")
            @DecimalMin(value = "-180", message = "-180 이상이어야 합니다.")
            @DecimalMax(value = "180", message = "180 이하여야 합니다.")
            @Digits(integer = 3, fraction = 6, message = "소수 여섯 자리 이하여야 합니다.")
            BigDecimal longitude
    ) {
    }
}
