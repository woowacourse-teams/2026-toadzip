package com.toadzip.backend.eligibility.domain;

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

    /**
     * 적용 조건(appliesWhen) 밖이라 기본 기준을 충족하지 않아도 막지 않는 조건. 가산 대상처럼 기준이 달라지는
     * 사람이라 직접 확인으로 보이지만 판정을 막지 않는다. 근거로 기본 기준과 비교한 값(base)을 그대로 싣는다.
     */
    public static RuleReading notApplied(RuleReading base) {
        Map<String, Object> values = new java.util.HashMap<>(base.comparedValues);
        values.put("reason", NOT_APPLIED);
        return new RuleReading(Status.DIRECT_CHECK_REQUIRED, Set.of(), values);
    }

    /** 신청자격 판정에서 지원 가능을 막는 결과인지. 충족과 적용 조건 밖인 조건만 막지 않는다. */
    public boolean blocksEligibility() {
        return status != Status.SATISFIED && !NOT_APPLIED.equals(comparedValues.get("reason"));
    }

    private static final String NOT_APPLIED = "NOT_APPLIED";

    public static RuleReading combine(List<RuleReading> readings, boolean all) {
        Map<String, Object> compared = Map.of("children", List.copyOf(readings));
        if (!all && readings.stream().anyMatch(reading -> reading.status == Status.SATISFIED)) {
            return new RuleReading(Status.SATISFIED, Set.of(), compared);
        }
        Set<ProfileField> missing = new LinkedHashSet<>();
        for (RuleReading reading : readings) {
            missing.addAll(reading.missingInputs);
        }
        if (all && readings.stream().anyMatch(reading -> reading.status == Status.NOT_SATISFIED)) {
            return new RuleReading(Status.NOT_SATISFIED, missing, compared);
        }
        if (readings.stream().anyMatch(reading -> reading.status == Status.DIRECT_CHECK_REQUIRED)) {
            return new RuleReading(Status.DIRECT_CHECK_REQUIRED, missing, compared);
        }
        if (!missing.isEmpty()) {
            return new RuleReading(Status.INPUT_NEEDED, missing, compared);
        }
        return matched(all, compared);
    }
}
