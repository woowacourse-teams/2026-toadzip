package com.toadzip.backend.housing.dto.response;

import java.time.LocalDate;
import java.util.List;

public record HousingComplexDetailResponse(
        long complexId,
        String name,
        String rentalType,
        AgencyResponse agency,
        HousingComplexAddressResponse address,
        LocalDate completionDate,
        String buildingType,
        Boolean hasElevator,
        String heatingType,
        String corridorType,
        Integer moveOutCountLastYear,
        Integer totalHouseholdCount,
        Integer totalParkingCount,
        List<String> images,
        String overviewImageUrl,
        List<HousingTypeDetailResponse> housingTypes,
        List<CurrentAnnouncementResponse> currentAnnouncements
) {

    public HousingComplexDetailResponse {
        images = List.copyOf(images);
        housingTypes = List.copyOf(housingTypes);
        currentAnnouncements = List.copyOf(currentAnnouncements);
    }
}
