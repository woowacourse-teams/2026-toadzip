package com.toadzip.backend.basiccomparison.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuleReading(
        Status status, Set<ProfileField> missingInputs, Map<String, Object> comparedValues) {
    public RuleReading {
        missingInputs = Set.copyOf(missingInputs);
        comparedValues = Map.copyOf(comparedValues);
    }

    public enum Status {
        SATISFIED,
        NOT_SATISFIED,
        INPUT_NEEDED,
        DIRECT_CHECK_REQUIRED
    }

    public static RuleReading matched(boolean matches, Map<String, Object> values) {
        if (matches) {
            return new RuleReading(Status.SATISFIED, Set.of(), values);
        }
        return new RuleReading(Status.NOT_SATISFIED, Set.of(), values);
    }

    public static RuleReading missing(Set<ProfileField> missing) {
        return new RuleReading(Status.INPUT_NEEDED, missing, Map.of());
    }

    public static RuleReading manual() {
        return new RuleReading(Status.DIRECT_CHECK_REQUIRED, Set.of(), Map.of());
    }

    public static RuleReading combine(List<RuleReading> readings, boolean all) {
        if (!all && readings.stream().anyMatch(reading -> reading.status == Status.SATISFIED)) {
            return new RuleReading(Status.SATISFIED, Set.of(), Map.of());
        }
        Set<ProfileField> missing = new LinkedHashSet<>();
        for (RuleReading reading : readings) {
            missing.addAll(reading.missingInputs);
        }
        if (all && readings.stream().anyMatch(reading -> reading.status == Status.NOT_SATISFIED)) {
            return new RuleReading(Status.NOT_SATISFIED, missing, Map.of());
        }
        if (readings.stream().anyMatch(reading -> reading.status == Status.DIRECT_CHECK_REQUIRED)) {
            return new RuleReading(Status.DIRECT_CHECK_REQUIRED, missing, Map.of());
        }
        if (!missing.isEmpty()) {
            return missing(missing);
        }
        return matched(all, Map.of());
    }
}
