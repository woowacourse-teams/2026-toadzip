package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import java.util.Set;

public final class ProfileRuleValidator {
    private final Set<Long> standardIds;

    public ProfileRuleValidator(Set<Long> standardIds) {
        this.standardIds = Set.copyOf(standardIds);
    }

    public void validate(ProfileRule rule) {
        validate(rule, 0, new NodeBudget());
    }

    private void validate(ProfileRule rule, int depth, NodeBudget count) {
        require(rule != null && rule.operator() != null, "기본 조건의 연산이 필요합니다.");
        require(depth <= 8 && count.next() <= 256, "기본 조건의 크기 또는 깊이를 초과했습니다.");
        if (rule.operator() == ProfileRule.Operator.ALL
                || rule.operator() == ProfileRule.Operator.ANY) {
            require(
                    !rule.children().isEmpty() && rule.children().size() <= 32,
                    "조건 조합은 비어 있을 수 없습니다.");
            require(
                    rule.field() == null
                            && rule.expected() == null
                            && rule.minimum() == null
                            && rule.maximum() == null
                            && rule.regions().isEmpty()
                            && rule.incomeLimit() == null,
                    "조건 조합에 개별 비교 설정을 섞을 수 없습니다.");
            for (ProfileRule child : rule.children()) {
                validate(child, depth + 1, count);
            }
            return;
        }
        require(rule.field() != null && rule.children().isEmpty(), "현재 입력 항목으로만 비교해야 합니다.");
        validateFields(rule);
        switch (rule.operator()) {
            case EQUALS -> equality(rule);
            case AGE_RANGE -> {
                require(
                        Set.of(ProfileField.BIRTH_DATE, ProfileField.YOUNGEST_CHILD)
                                .contains(rule.field()),
                        "나이 비교 항목이 아닙니다.");
                range(rule, 150);
            }
            case NUMBER_RANGE -> {
                require(Set.of(ProfileField.Type.COUNT, ProfileField.Type.MONTHS).contains(rule.field().type()),
                        "인원 또는 거주기간만 수치 범위로 비교합니다.");
                range(rule, 1800);
            }
            case DAYS_FROM_REFERENCE -> {
                require(rule.field() == ProfileField.CURRENT_LEASE_END_DATE, "계약 종료일 비교 항목이 아닙니다.");
                require(rule.minimum() != null && rule.maximum() != null
                                && rule.minimum() >= -36525 && rule.maximum() <= 36525
                                && rule.minimum() <= rule.maximum(), "기준일 전후 일수 범위가 올바르지 않습니다.");
            }
            case MONTHS_SINCE -> {
                require(rule.field() == ProfileField.MARRIAGE_EVENT, "혼인신고일만 혼인기간으로 비교합니다.");
                range(rule, 1800);
            }
            case REGION_IN ->
                    require(
                            rule.field() == ProfileField.RESIDENCE_REGION
                                    && !rule.regions().isEmpty(),
                            "거주지역 목록이 필요합니다.");
            case MONEY_CEILING ->
                    require(
                            rule.field().type() == ProfileField.Type.MONEY
                                    && rule.maximum() != null
                                    && rule.maximum() >= 0,
                            "금액 상한이 필요합니다.");
            case INCOME_CEILING -> income(rule);
            default -> throw new InvalidEligibilityException("지원하지 않는 기본 비교입니다.");
        }
    }

    private void validateFields(ProfileRule rule) {
        require(
                (rule.operator() == ProfileRule.Operator.EQUALS) == (rule.expected() != null),
                "일치 비교 설정이 올바르지 않습니다.");
        require(
                rule.operator() == ProfileRule.Operator.REGION_IN || rule.regions().isEmpty(),
                "지역 설정이 다른 조건에 있습니다.");
        require(
                (rule.operator() == ProfileRule.Operator.INCOME_CEILING)
                        == (rule.incomeLimit() != null),
                "소득표 설정이 올바르지 않습니다.");
        if (!Set.of(ProfileRule.Operator.AGE_RANGE, ProfileRule.Operator.MONTHS_SINCE,
                        ProfileRule.Operator.NUMBER_RANGE, ProfileRule.Operator.DAYS_FROM_REFERENCE)
                .contains(rule.operator())) {
            require(rule.minimum() == null, "이 비교는 하한을 사용하지 않습니다.");
        }
        if (!Set.of(
                        ProfileRule.Operator.AGE_RANGE,
                        ProfileRule.Operator.MONTHS_SINCE,
                        ProfileRule.Operator.MONEY_CEILING,
                        ProfileRule.Operator.NUMBER_RANGE,
                        ProfileRule.Operator.DAYS_FROM_REFERENCE)
                .contains(rule.operator())) {
            require(rule.maximum() == null, "이 비교는 고정 상한을 사용하지 않습니다.");
        }
    }

    private void equality(ProfileRule rule) {
        if (rule.field().type() == ProfileField.Type.BOOLEAN) {
            require(Set.of("true", "false").contains(rule.expected()), "불리언 기준이 올바르지 않습니다.");
            return;
        }
        if (rule.field() == ProfileField.FAMILY_TYPE) {
            require(
                    Set.of("UNMARRIED", "SINGLE_PARENT", "COUPLE_OR_ENGAGED")
                            .contains(rule.expected()),
                    "가족 형태 기준이 올바르지 않습니다.");
            return;
        }
        require(
                rule.field().type() == ProfileField.Type.COUNT
                        && rule.expected().matches("[0-9]{1,3}"),
                "일치 비교에 지원하지 않는 입력입니다.");
    }

    private void range(ProfileRule rule, long limit) {
        require(
                rule.minimum() != null
                        && rule.maximum() != null
                        && rule.minimum() >= 0
                        && rule.maximum() >= rule.minimum()
                        && rule.maximum() <= limit,
                "기간 범위가 올바르지 않습니다.");
    }

    private void income(ProfileRule rule) {
        require(
                Set.of(
                                ProfileField.APPLICANT_MONTHLY_INCOME,
                                ProfileField.HOUSEHOLD_MONTHLY_INCOME,
                                ProfileField.PARENTS_MONTHLY_INCOME)
                        .contains(rule.field()),
                "소득 입력만 소득표와 비교할 수 있습니다.");
        var limit = rule.incomeLimit();
        require(standardIds.contains(limit.standardTableId()), "소득 기준표를 찾을 수 없습니다.");
        require(limit.percent() > 0 && limit.percent() <= 1000, "소득 비율이 올바르지 않습니다.");
        require(
                (limit.fixedFamilyCount() == null) != (limit.familyCountField() == null),
                "가구원수 기준을 하나만 선택해야 합니다.");
        if (limit.familyCountField() != null) {
            require(
                    limit.familyCountField() == ProfileField.REGISTERED_FAMILY_COUNT,
                    "현재 수집하지 않는 가구원수를 사용할 수 없습니다.");
        }
        if (limit.fixedFamilyCount() != null) {
            require(
                    limit.fixedFamilyCount() > 0 && limit.fixedFamilyCount() <= 100,
                    "고정 가구원수가 올바르지 않습니다.");
        }
        require(
                limit.sizeExtraPercent().entrySet().stream()
                        .allMatch(
                                entry ->
                                        entry.getKey() > 0
                                                && entry.getKey() <= 100
                                                && entry.getValue() >= 0
                                                && entry.getValue() <= 1000),
                "가구원수 가산이 올바르지 않습니다.");
    }

    private static final class NodeBudget {
        private int count;

        int next() {
            return ++count;
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidEligibilityException(message);
        }
    }
}
