package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CurrentSupplyConditionRow(
        long announcementId,
        long supplyRowId,
        long housingTypeId,
        long targetId,
        String target,
        BigDecimal deposit,
        BigDecimal monthlyRent,
        BigDecimal convertibleDeposit,
        LocalDate postedDate
) {
}
