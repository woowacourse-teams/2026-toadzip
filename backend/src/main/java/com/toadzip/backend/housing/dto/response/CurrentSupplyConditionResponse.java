package com.toadzip.backend.housing.dto.response;

import java.time.LocalDate;

public record CurrentSupplyConditionResponse(
        String target,
        Long deposit,
        Long monthlyRent,
        Long convertibleDeposit,
        LocalDate postedDate
) {
}
