package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.InputFact.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves only the fixed input vocabulary; list gaps never become invented user facts. */
public final class FactResolver {
    public static final Set<String> DERIVED =
            Set.of(
                    "AGE_AT_REFERENCE",
                    "IS_MARRIED_AT_REFERENCE",
                    "IS_ENGAGED_AT_REFERENCE",
                    "MARRIAGE_MONTHS_AT_REFERENCE",
                    "YOUNGEST_CHILD_AGE_AT_REFERENCE",
                    "CHILDREN_BORN_SINCE_2023_03_28",
                    "HAS_SAVINGS_ACCOUNT",
                    "APPLICANT_AND_PARENTS_MONTHLY_INCOME",
                    "APPLICANT_AND_PARENTS_COUNT",
                    "DEPENDENTS_COUNT");

    private final EligibilityProfile profile;
    private final LocalDate referenceDate;
    private final Long candidateSize;

    public FactResolver(EligibilityProfile profile, LocalDate referenceDate, Long candidateSize) {
        this.profile = profile;
        this.referenceDate = referenceDate;
        this.candidateSize = candidateSize;
    }

    public FactReading resolve(String code) {
        return switch (code) {
            case "AGE_AT_REFERENCE" -> elapsed(BIRTH_DATE, ChronoUnit.YEARS);
            case "IS_MARRIED_AT_REFERENCE" -> marriage(false);
            case "IS_ENGAGED_AT_REFERENCE" -> marriage(true);
            case "MARRIAGE_MONTHS_AT_REFERENCE" -> marriageMonths();
            case "YOUNGEST_CHILD_AGE_AT_REFERENCE" -> youngestAge();
            case "CHILDREN_BORN_SINCE_2023_03_28" -> childrenBornSince();
            case "HAS_SAVINGS_ACCOUNT" -> savingsAccount();
            case "APPLICANT_AND_PARENTS_MONTHLY_INCOME" ->
                    sum(APPLICANT_MONTHLY_INCOME, PARENTS_MONTHLY_INCOME);
            case "APPLICANT_AND_PARENTS_COUNT" -> offset(PARENTS_COUNT, 1);
            case "DEPENDENTS_COUNT" -> offset(FAMILY_MEMBER_COUNT, -1);
            default -> input(InputFact.valueOf(code));
        };
    }

    public static InputFact.ValueType type(String code) {
        if (!DERIVED.contains(code)) {
            return InputFact.valueOf(code).valueType();
        }
        return switch (code) {
            case "IS_MARRIED_AT_REFERENCE", "IS_ENGAGED_AT_REFERENCE", "HAS_SAVINGS_ACCOUNT" ->
                    InputFact.ValueType.BOOLEAN;
            case "APPLICANT_AND_PARENTS_MONTHLY_INCOME" -> InputFact.ValueType.KRW;
            case "MARRIAGE_MONTHS_AT_REFERENCE" -> InputFact.ValueType.MONTHS;
            default -> InputFact.ValueType.INTEGER;
        };
    }

    private FactReading input(InputFact fact) {
        if (fact == FAMILY_MEMBER_COUNT && candidateSize != null) {
            return FactReading.known(candidateSize, Set.of(fact));
        }
        InputValue input = profile.value(fact);
        if (input.state() == ValueState.UNKNOWN) {
            return FactReading.missing(Set.of(fact));
        }
        if (input.state() == ValueState.PROVIDED) {
            return FactReading.known(input.value(), Set.of(fact));
        }
        return switch (fact.valueType()) {
            case INTEGER, KRW, MONTHS -> FactReading.known(0, Set.of(fact));
            case BOOLEAN -> FactReading.known(false, Set.of(fact));
            default -> FactReading.notApplicable(Set.of(fact));
        };
    }

    private FactReading elapsed(InputFact fact, ChronoUnit unit) {
        FactReading reading = input(fact);
        if (reading.state() != ValueState.PROVIDED) {
            return reading;
        }
        return FactReading.known(
                unit.between((LocalDate) reading.value(), referenceDate), reading.inputs());
    }

    private FactReading marriage(boolean engaged) {
        FactReading family = input(FAMILY_TYPE);
        if (family.state() != ValueState.PROVIDED) {
            return family;
        }
        if (!"COUPLE_OR_ENGAGED".equals(family.value())) {
            return FactReading.known(false, family.inputs());
        }
        FactReading date = input(MARRIAGE_REPORT_DATE);
        if (date.state() == ValueState.UNKNOWN) {
            return date;
        }
        if (date.state() == ValueState.NOT_APPLICABLE) {
            return FactReading.missing(Set.of(MARRIAGE_REPORT_DATE));
        }
        boolean after = ((LocalDate) date.value()).isAfter(referenceDate);
        return FactReading.known(after == engaged, union(family, date));
    }

    private FactReading marriageMonths() {
        FactReading married = marriage(false);
        if (married.state() != ValueState.PROVIDED) {
            return married;
        }
        if (!Boolean.TRUE.equals(married.value())) {
            return FactReading.notApplicable(married.inputs());
        }
        return elapsed(MARRIAGE_REPORT_DATE, ChronoUnit.MONTHS);
    }

    private FactReading youngestAge() {
        FactReading children = input(HAS_CHILDREN);
        if (children.state() != ValueState.PROVIDED) {
            return children;
        }
        if (Boolean.FALSE.equals(children.value())) {
            return FactReading.notApplicable(children.inputs());
        }
        return elapsed(YOUNGEST_CHILD_BIRTH_DATE, ChronoUnit.YEARS);
    }

    private FactReading childrenBornSince() {
        FactReading children = input(HAS_CHILDREN);
        if (children.state() != ValueState.PROVIDED) {
            return children;
        }
        if (Boolean.FALSE.equals(children.value())) {
            return FactReading.known(0, children.inputs());
        }
        FactReading youngest = input(YOUNGEST_CHILD_BIRTH_DATE);
        FactReading count = input(CHILDREN_COUNT);
        Set<InputFact> missing = missing(youngest, count);
        if (!missing.isEmpty()) {
            return FactReading.missing(missing);
        }
        if (youngest.state() == ValueState.NOT_APPLICABLE
                || count.state() == ValueState.NOT_APPLICABLE) {
            return FactReading.missing(Set.of(YOUNGEST_CHILD_BIRTH_DATE, CHILDREN_COUNT));
        }
        Set<InputFact> inputs = union(children, youngest, count);
        if (((LocalDate) youngest.value()).isBefore(LocalDate.of(2023, 3, 28))) {
            return FactReading.known(0, inputs);
        }
        if (count.number().compareTo(BigDecimal.ONE) == 0) {
            return FactReading.known(1, inputs);
        }
        return new FactReading(ValueState.PROVIDED, BigDecimal.ONE, count.number(), inputs, "G06");
    }

    private FactReading savingsAccount() {
        for (InputFact fact : List.of(SAVINGS_PAYMENT_COUNT, SAVINGS_PAID_AMOUNT, SAVINGS_PERIOD)) {
            InputValue value = profile.value(fact);
            if (value.state() == ValueState.PROVIDED) {
                return FactReading.known(true, Set.of(fact));
            }
            if (value.state() == ValueState.NOT_APPLICABLE) {
                return FactReading.known(false, Set.of(fact));
            }
        }
        return FactReading.missing(Set.of(SAVINGS_PAYMENT_COUNT));
    }

    private FactReading sum(InputFact first, InputFact second) {
        FactReading left = input(first);
        FactReading right = input(second);
        Set<InputFact> missing = missing(left, right);
        if (!missing.isEmpty()) {
            return FactReading.missing(missing);
        }
        return FactReading.known(left.number().add(right.number()), union(left, right));
    }

    private FactReading offset(InputFact fact, long amount) {
        FactReading reading = input(fact);
        if (reading.state() != ValueState.PROVIDED) {
            return reading;
        }
        return FactReading.known(
                reading.number().add(BigDecimal.valueOf(amount)), reading.inputs());
    }

    private static Set<InputFact> missing(FactReading... readings) {
        Set<InputFact> inputs = new LinkedHashSet<>();
        for (FactReading reading : readings) {
            if (reading.state() == ValueState.UNKNOWN) {
                inputs.addAll(reading.inputs());
            }
        }
        return inputs;
    }

    private static Set<InputFact> union(FactReading... readings) {
        Set<InputFact> inputs = new LinkedHashSet<>();
        for (FactReading reading : readings) {
            inputs.addAll(reading.inputs());
        }
        return inputs;
    }
}
