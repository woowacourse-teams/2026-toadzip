package com.toadzip.backend.housing.dto.response;

import java.time.Instant;

public record BasicRentalConditionResponse(
        Long deposit,
        Long monthlyRent,
        String source,
        String sourceUrl,
        Instant collectedAt
) {
}
