package com.toadzip.backend.eligibility.domain;

import java.math.BigDecimal;
import java.util.Set;

public record FactReading(
        ValueState state,
        Object value,
        BigDecimal upperBound,
        Set<InputFact> inputs,
        String inputGap) {
    public FactReading {
        inputs = Set.copyOf(inputs);
    }

    public static FactReading known(Object value, Set<InputFact> inputs) {
        if (value instanceof Number number) {
            return new FactReading(
                    ValueState.PROVIDED, new BigDecimal(number.toString()), null, inputs, null);
        }
        return new FactReading(ValueState.PROVIDED, value, null, inputs, null);
    }

    public static FactReading missing(Set<InputFact> inputs) {
        return new FactReading(ValueState.UNKNOWN, null, null, inputs, null);
    }

    public static FactReading notApplicable(Set<InputFact> inputs) {
        return new FactReading(ValueState.NOT_APPLICABLE, null, null, inputs, null);
    }

    public BigDecimal number() {
        return (BigDecimal) value;
    }
}
