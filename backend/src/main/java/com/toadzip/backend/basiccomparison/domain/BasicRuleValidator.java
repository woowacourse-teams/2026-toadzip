package com.toadzip.backend.basiccomparison.domain;

import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;

import java.util.Set;

public final class BasicRuleValidator {
    private final Set<Long> standardIds;

    public BasicRuleValidator(Set<Long> standardIds) {
        this.standardIds = Set.copyOf(standardIds);
    }

    public void validate(BasicRule rule) {
        validate(rule, 0, new NodeBudget());
    }

    private void validate(BasicRule rule, int depth, NodeBudget count) {
        require(rule != null && rule.operator() != null, "기본 조건의 연산이 필요합니다.");
        require(depth <= 8 && count.next() <= 256, "기본 조건의 크기 또는 깊이를 초과했습니다.");
        if (rule.operator() == BasicRule.Operator.ALL
                || rule.operator() == BasicRule.Operator.ANY) {
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
            for (BasicRule child : rule.children()) {
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
            default -> throw new InvalidComparisonException("지원하지 않는 기본 비교입니다.");
        }
    }

    private void validateFields(BasicRule rule) {
        require(
                (rule.operator() == BasicRule.Operator.EQUALS) == (rule.expected() != null),
                "일치 비교 설정이 올바르지 않습니다.");
        require(
                rule.operator() == BasicRule.Operator.REGION_IN || rule.regions().isEmpty(),
                "지역 설정이 다른 조건에 있습니다.");
        require(
                (rule.operator() == BasicRule.Operator.INCOME_CEILING)
                        == (rule.incomeLimit() != null),
                "소득표 설정이 올바르지 않습니다.");
        if (!Set.of(BasicRule.Operator.AGE_RANGE, BasicRule.Operator.MONTHS_SINCE)
                .contains(rule.operator())) {
            require(rule.minimum() == null, "이 비교는 하한을 사용하지 않습니다.");
        }
        if (!Set.of(
                        BasicRule.Operator.AGE_RANGE,
                        BasicRule.Operator.MONTHS_SINCE,
                        BasicRule.Operator.MONEY_CEILING)
                .contains(rule.operator())) {
            require(rule.maximum() == null, "이 비교는 고정 상한을 사용하지 않습니다.");
        }
    }

    private void equality(BasicRule rule) {
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

    private void range(BasicRule rule, long limit) {
        require(
                rule.minimum() != null
                        && rule.maximum() != null
                        && rule.minimum() >= 0
                        && rule.maximum() >= rule.minimum()
                        && rule.maximum() <= limit,
                "기간 범위가 올바르지 않습니다.");
    }

    private void income(BasicRule rule) {
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
            throw new InvalidComparisonException(message);
        }
    }
}
