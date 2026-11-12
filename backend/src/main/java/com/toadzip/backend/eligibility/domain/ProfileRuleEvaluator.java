package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.ProfileField.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

public final class ProfileRuleEvaluator {
    private final LocalDate referenceDate;
    private final EligibilityProfile profile;
    private final Map<Long, IncomeStandard> standards;
    private final RegionMembership regions;

    public ProfileRuleEvaluator(
            LocalDate referenceDate,
            EligibilityProfile profile,
            Map<Long, IncomeStandard> standards,
            RegionMembership regions) {
        this.referenceDate = referenceDate;
        this.profile = profile;
        this.standards = Map.copyOf(standards);
        this.regions = regions;
    }

    public RuleReading evaluate(ProfileRule rule) {
        if (rule.operator() == ProfileRule.Operator.ALL
                || rule.operator() == ProfileRule.Operator.ANY) {
            return RuleReading.combine(
                    rule.children().stream().map(this::evaluate).toList(),
                    rule.operator() == ProfileRule.Operator.ALL);
        }
        var access = profile.access(rule.field());
        if (access.state() == EligibilityProfile.AccessState.NOT_COLLECTED) {
            return knownFromFamilyType(rule);
        }
        if (access.state() == EligibilityProfile.AccessState.WAITING) {
            return RuleReading.missing(access.dependencies());
        }
        InputValue input = profile.value(rule.field());
        if (knownAbsentVehicle(rule)) {
            return RuleReading.matched(true, Map.of("field", rule.field(), "vehicleOwned", false));
        }
        if (input.state() == ValueState.NOT_APPLICABLE
                && rule.field() == CHILDREN_COUNT_INCLUDING_FETUS
                && profile.provided(HAS_CHILDREN_OR_PREGNANCY, false)) {
            if (rule.operator() == ProfileRule.Operator.EQUALS) {
                return equality(rule, 0L);
            }
            return range(rule, 0L);
        }
        if (input.state() == ValueState.NOT_APPLICABLE) {
            return RuleReading.matched(false, Map.of("field", rule.field(), "inputState", input.state()));
        }
        if (input.state() != ValueState.PROVIDED) {
            return RuleReading.missing(Set.of(rule.field()));
        }
        Object value = input.value();
        return switch (rule.operator()) {
            case EQUALS -> equality(rule, value);
            case AGE_RANGE -> age(rule, value);
            case NUMBER_RANGE -> range(rule, (Long) value);
            case DAYS_FROM_REFERENCE -> range(rule, ChronoUnit.DAYS.between(referenceDate, (LocalDate) value));
            case MONTHS_SINCE -> months(rule, value);
            case REGION_IN -> region(rule, (String) value);
            case MONEY_CEILING ->
                    amount(
                            rule.field(),
                            (Long) value,
                            BigDecimal.valueOf(rule.maximum()),
                            Map.of());
            case INCOME_CEILING -> income(rule, (Long) value);
            default -> throw new IllegalStateException("검증되지 않은 비교 연산입니다.");
        };
    }

    /**
     * 가족 형태 때문에 묻지 않은 값. 미혼이면 자녀·임신이 없고 부부가 아니면 혼인·맞벌이가 없다고 보고 비교한다.
     * 일반 사용자가 스스로 판단하는 수준이라 직접 확인으로 미루지 않는다. 그 밖의 값(부모 소득 등)은 직접 확인이다.
     */
    private RuleReading knownFromFamilyType(ProfileRule rule) {
        if (rule.field().flow() != ProfileField.Flow.WITH_FAMILY && rule.field().flow() != ProfileField.Flow.COUPLE) {
            return RuleReading.manual();
        }
        if (rule.field().type() == ProfileField.Type.BOOLEAN) {
            return equality(rule, false);
        }
        if (rule.field() == CHILDREN_COUNT_INCLUDING_FETUS) {
            return rule.operator() == ProfileRule.Operator.EQUALS ? equality(rule, 0L) : range(rule, 0L);
        }
        return RuleReading.matched(false, Map.of("field", rule.field(), "inputState", ValueState.NOT_APPLICABLE));
    }

    private boolean knownAbsentVehicle(ProfileRule rule) {
        if (rule.operator() != ProfileRule.Operator.MONEY_CEILING) {
            return false;
        }
        if (rule.field() == APPLICANT_CAR_VALUE) {
            return profile.provided(APPLICANT_OWNS_CAR, false);
        }
        if (rule.field() == FAMILY_CAR_VALUE) {
            return profile.provided(FAMILY_OWNS_CAR, false);
        }
        return false;
    }

    private RuleReading equality(ProfileRule rule, Object value) {
        return RuleReading.matched(
                value.toString().equals(rule.expected()),
                Map.of("field", rule.field(), "providedValue", value, "expected", rule.expected(), "operator", "EQ"));
    }

    private RuleReading age(ProfileRule rule, Object value) {
        // 출산 예정이면 기준일에 막 태어난 아이(0세)로 본다. 일반 사용자가 '자녀 있음'으로 지원해 볼 수준이다.
        if (value instanceof DateEvent event && !event.actual()) {
            return range(rule, 0L);
        }
        LocalDate birth = actualDate(value);
        if (birth == null) {
            return RuleReading.manual();
        }
        if (birth.isAfter(referenceDate) && value instanceof DateEvent) {
            return RuleReading.manual();
        }
        long years = Period.between(birth, referenceDate).getYears();
        return range(rule, years);
    }

    private RuleReading months(ProfileRule rule, Object value) {
        // 혼인 예정이면 기준일에 막 혼인한 것(0개월)으로 본다. 예비 신혼부부가 신혼부부로 지원해 볼 수준이다.
        if (value instanceof DateEvent event && !event.actual()) {
            return RuleReading.matched(rule.minimum() <= 0 && rule.maximum() >= 0, Map.of(
                    "field", rule.field(), "providedValue", event, "operator", "BETWEEN_INCLUSIVE",
                    "unit", "MONTHS", "comparedValue", 0L, "minimum", rule.minimum(), "maximum", rule.maximum(),
                    "referenceDate", referenceDate));
        }
        LocalDate marriage = actualDate(value);
        if (marriage == null) {
            return RuleReading.manual();
        }
        if (marriage.isAfter(referenceDate)) {
            return RuleReading.manual();
        }
        LocalDate earliest = referenceDate.minusMonths(rule.maximum());
        LocalDate latest = referenceDate.minusMonths(rule.minimum());
        return RuleReading.matched(
                !marriage.isBefore(earliest) && !marriage.isAfter(latest),
                Map.of(
                        "field",
                        rule.field(),
                        "providedValue",
                        marriage,
                        "operator", "BETWEEN_INCLUSIVE",
                        "earliest",
                        earliest,
                        "latest",
                        latest));
    }

    private LocalDate actualDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof DateEvent event && event.actual()) {
            return event.date();
        }
        return null;
    }

    private RuleReading range(ProfileRule rule, long value) {
        return RuleReading.matched(
                value >= rule.minimum() && value <= rule.maximum(),
                Map.of(
                        "field",
                        rule.field(),
                        "providedValue", suppliedRangeValue(rule, value),
                        "operator", "BETWEEN_INCLUSIVE",
                        "unit", comparisonUnit(rule),
                        "comparedValue",
                        value,
                        "minimum",
                        rule.minimum(),
                        "maximum",
                        rule.maximum(),
                        "referenceDate",
                        referenceDate));
    }

    private Object suppliedRangeValue(ProfileRule rule, long compared) {
        Object value = profile.value(rule.field()).value();
        if (value == null) {
            return compared;
        }
        return value;
    }

    private String comparisonUnit(ProfileRule rule) {
        if (rule.operator() == ProfileRule.Operator.AGE_RANGE) {
            return "YEARS";
        }
        if (rule.operator() == ProfileRule.Operator.DAYS_FROM_REFERENCE) {
            return "DAYS";
        }
        return rule.field().unit();
    }

    private RuleReading region(ProfileRule rule, String value) {
        RegionMembership.Match match = regions.match(Set.copyOf(rule.regions()), value);
        if (match == RegionMembership.Match.INSUFFICIENT_PRECISION) {
            return new RuleReading(RuleReading.Status.INPUT_NEEDED, Set.of(rule.field()),
                    Map.of("reason", "REGION_PRECISION", "providedValue", value, "requiredRegions", rule.regions()));
        }
        return RuleReading.matched(
                match == RegionMembership.Match.MATCH,
                Map.of(
                        "field",
                        rule.field(),
                        "providedValue",
                        value,
                        "requiredRegions",
                        rule.regions()));
    }

    private RuleReading income(ProfileRule rule, long value) {
        ProfileRule.IncomeLimit limit = rule.incomeLimit();
        Long familyCount = limit.fixedFamilyCount();
        if (limit.familyCountField() != null) {
            InputValue input = profile.value(limit.familyCountField());
            if (input.state() != ValueState.PROVIDED) {
                return RuleReading.missing(Set.of(limit.familyCountField()));
            }
            familyCount = (Long) input.value();
        }
        IncomeStandard standard = standards.get(limit.standardTableId());
        if (standard == null) {
            throw new IllegalStateException("공개 내용의 소득 기준표가 없습니다.");
        }
        var standardAmount = standard.amount(familyCount);
        if (standardAmount.isEmpty()) {
            return RuleReading.manual();
        }
        int percentage = limit.percent() + limit.sizeExtraPercent().getOrDefault(familyCount, 0);
        BigDecimal ceiling =
                standardAmount
                        .get()
                        .multiply(BigDecimal.valueOf(percentage))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        return amount(
                rule.field(),
                value,
                ceiling,
                Map.of(
                        "standardKind",
                        standard.kind(),
                        "standardYear",
                        standard.baseYear(),
                        "familyCount",
                        familyCount,
                        "percent",
                        percentage));
    }

    private RuleReading amount(
            ProfileField field, long value, BigDecimal ceiling, Map<String, Object> basis) {
        return RuleReading.matched(
                BigDecimal.valueOf(value).compareTo(ceiling) <= 0,
                Map.of(
                        "field",
                        field,
                        "providedValue",
                        value,
                        "fieldLabel", field.label(),
                        "operator", "LTE",
                        "comparisonLimit",
                        ceiling,
                        "unit",
                        field.unit(),
                        "basis",
                        basis));
    }
}
