package com.toadzip.backend.announcement.dto.response;

public record ImportComplexCandidateResponse(
        long housingComplexId,
        String name,
        String roadAddress,
        String rentalType,
        String agencyCode
) {
}
