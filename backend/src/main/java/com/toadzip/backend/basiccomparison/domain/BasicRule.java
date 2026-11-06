package com.toadzip.backend.basiccomparison.domain;

import java.util.List;
import java.util.Map;

/** Only predicates over the closed current input contract; no selection or special-status rules. */
public record BasicRule(
        Operator operator,
        ProfileField field,
        String expected,
        Long minimum,
        Long maximum,
        List<String> regions,
        IncomeLimit incomeLimit,
        List<BasicRule> children) {
    public BasicRule {
        regions = ComparisonContent.immutable(regions);
        children = ComparisonContent.immutable(children);
    }

    public enum Operator {
        EQUALS,
        AGE_RANGE,
        NUMBER_RANGE,
        DAYS_FROM_REFERENCE,
        MONTHS_SINCE,
        REGION_IN,
        MONEY_CEILING,
        INCOME_CEILING,
        ALL,
        ANY
    }

    public record IncomeLimit(
            long standardTableId,
            int percent,
            Map<Long, Integer> sizeExtraPercent,
            ProfileField familyCountField,
            Long fixedFamilyCount) {
        public IncomeLimit {
            if (sizeExtraPercent == null) {
                sizeExtraPercent = Map.of();
            }
            sizeExtraPercent = Map.copyOf(sizeExtraPercent);
        }
    }
}
