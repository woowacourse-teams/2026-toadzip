package com.toadzip.backend.housing.dto.request;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record AdminHousingComplexUpdateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 255) String name,
        @NotNull RentalType rentalType,
        @NotNull AgencyCode agencyCode,
        @NotNull @Valid AdminHousingComplexCreateRequest.AddressRequest address,
        @NotNull @PositiveOrZero Integer totalHouseholdCount,
        LocalDate completionDate,
        @Size(max = 255) String heatingType,
        @Size(max = 255) String buildingType,
        @Size(max = 255) String corridorType,
        Boolean hasElevator,
        @NotNull @PositiveOrZero Integer totalParkingCount,
        @Size(max = 255) @Pattern(regexp = "^https?://[^\\s]+$") String overviewImageUrl,
        @PositiveOrZero Integer moveOutCountLastYear) { }
