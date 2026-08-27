package com.toadzip.backend.housing.dto.response;

import java.time.LocalDate;

public record RepresentativeAnnouncementResponse(
        long announcementId,
        String publicationType,
        String applicationStatus,
        LocalDate applicationEndAt,
        Integer dDay
) {
}
