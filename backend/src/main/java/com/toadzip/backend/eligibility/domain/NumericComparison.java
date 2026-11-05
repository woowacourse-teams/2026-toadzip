package com.toadzip.backend.eligibility.domain;

import java.math.BigDecimal;

/** Interval comparison includes interior values, including equality and bounded-range operators. */
public final class NumericComparison {
    private NumericComparison() {}

    public static ConditionOutcome compare(
            String operator,
            BigDecimal low,
            BigDecimal high,
            BigDecimal thresholdLow,
            BigDecimal thresholdHigh,
            BigDecimal rangeEnd) {
        return switch (operator) {
            case "LTE" ->
                    decide(high.compareTo(thresholdLow) <= 0, low.compareTo(thresholdHigh) > 0);
            case "LT" ->
                    decide(high.compareTo(thresholdLow) < 0, low.compareTo(thresholdHigh) >= 0);
            case "GTE" ->
                    decide(low.compareTo(thresholdHigh) >= 0, high.compareTo(thresholdLow) < 0);
            case "GT" ->
                    decide(low.compareTo(thresholdHigh) > 0, high.compareTo(thresholdLow) <= 0);
            case "EQ" -> equality(low, high, thresholdLow, thresholdHigh);
            case "NEQ" -> invert(equality(low, high, thresholdLow, thresholdHigh));
            case "BETWEEN" ->
                    decide(
                            low.compareTo(thresholdHigh) >= 0 && high.compareTo(rangeEnd) <= 0,
                            high.compareTo(thresholdLow) < 0 || low.compareTo(rangeEnd) > 0);
            default -> throw new IllegalArgumentException("Unsupported numeric operator");
        };
    }

    private static ConditionOutcome equality(
            BigDecimal low, BigDecimal high, BigDecimal first, BigDecimal last) {
        return decide(
                low.compareTo(high) == 0 && first.compareTo(last) == 0 && low.compareTo(first) == 0,
                high.compareTo(first) < 0 || low.compareTo(last) > 0);
    }

    private static ConditionOutcome decide(boolean definitelyTrue, boolean definitelyFalse) {
        if (definitelyTrue) {
            return ConditionOutcome.SATISFIED;
        }
        if (definitelyFalse) {
            return ConditionOutcome.NOT_SATISFIED;
        }
        return ConditionOutcome.UNDETERMINED;
    }

    private static ConditionOutcome invert(ConditionOutcome outcome) {
        return switch (outcome) {
            case SATISFIED -> ConditionOutcome.NOT_SATISFIED;
            case NOT_SATISFIED -> ConditionOutcome.SATISFIED;
            default -> outcome;
        };
    }
}
