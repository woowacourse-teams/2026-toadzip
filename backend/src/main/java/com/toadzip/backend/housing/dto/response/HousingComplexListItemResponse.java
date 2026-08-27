package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;

public record HousingComplexListItemResponse(
        long complexId,
        String thumbnailImageUrl,
        String regionName,
        String name,
        String rentalType,
        AgencyResponse agency,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMin,
        Long monthlyRentMax,
        RepresentativeAnnouncementResponse representativeAnnouncement
) {
}
