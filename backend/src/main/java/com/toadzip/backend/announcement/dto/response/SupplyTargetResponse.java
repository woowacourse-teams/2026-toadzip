package com.toadzip.backend.announcement.dto.response;

public record SupplyTargetResponse(
        long supplyTargetId,
        String target,
        String priority,
        Integer supplyHouseholdCount,
        Integer waitlistCount,
        Long deposit,
        Long monthlyRent,
        Long convertibleDeposit,
        String applicationCondition
) {
}
