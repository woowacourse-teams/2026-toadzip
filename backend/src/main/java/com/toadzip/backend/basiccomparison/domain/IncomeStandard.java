package com.toadzip.backend.basiccomparison.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public record IncomeStandard(
        long id,
        String kind,
        int baseYear,
        String sourceNote,
        Long extraPerPersonKrw,
        long maximumHouseholdSize,
        Map<Long, Long> amounts) {
    public IncomeStandard {
        amounts = Map.copyOf(amounts);
    }

    public Optional<BigDecimal> amount(long householdSize) {
        if (householdSize < 1) {
            return Optional.empty();
        }
        Long exact = amounts.get(householdSize);
        if (exact != null) {
            return Optional.of(BigDecimal.valueOf(exact));
        }
        Long maximum = amounts.get(maximumHouseholdSize);
        if (householdSize <= maximumHouseholdSize || maximum == null || extraPerPersonKrw == null) {
            return Optional.empty();
        }
        BigDecimal extra =
                BigDecimal.valueOf(householdSize - maximumHouseholdSize)
                        .multiply(BigDecimal.valueOf(extraPerPersonKrw));
        return Optional.of(BigDecimal.valueOf(maximum).add(extra));
    }
}
