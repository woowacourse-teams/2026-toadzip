package com.toadzip.backend.housing.dto.response;

public record CurrentSupplyConditionResponse(
        String target,
        Long deposit,
        Long monthlyRent,
        Long convertibleDeposit
) {
}
