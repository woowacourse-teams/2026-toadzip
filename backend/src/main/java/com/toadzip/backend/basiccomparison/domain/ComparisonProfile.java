package com.toadzip.backend.basiccomparison.domain;

import static com.toadzip.backend.basiccomparison.domain.ProfileField.*;

import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;

import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ComparisonProfile(
        FamilyDetailsState familyDetailsState, Map<ProfileField, InputValue> facts) {
    public static final int CONTRACT_VERSION = 2;

    public ComparisonProfile {
        if (familyDetailsState == null) {
            familyDetailsState = FamilyDetailsState.NOT_STARTED;
        }
        Map<ProfileField, InputValue> normalized = new EnumMap<>(ProfileField.class);
        for (ProfileField field : ProfileField.values()) {
            normalized.put(field, InputValue.unknown());
        }
        if (facts != null) {
            facts.forEach(
                    (field, input) -> {
                        if (field == null || input == null) {
                            throw new InvalidComparisonException("입력 항목과 상태가 필요합니다.");
                        }
                        normalized.put(field, input.normalize(field));
                    });
        }
        if (familyDetailsState != FamilyDetailsState.PROVIDED
                && normalized.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        entry.getKey().optional()
                                                && entry.getValue().state()
                                                        == ValueState.PROVIDED)) {
            throw new InvalidComparisonException("가족정보를 제공하려면 제공 상태를 선택해야 합니다.");
        }
        facts = Map.copyOf(normalized);
    }

    public static ComparisonProfile empty() {
        return new ComparisonProfile(FamilyDetailsState.NOT_STARTED, Map.of());
    }

    public InputValue value(ProfileField field) {
        return facts.get(field);
    }

    public boolean provided(ProfileField field, Object expected) {
        return value(field).state() == ValueState.PROVIDED
                && Objects.equals(value(field).value(), expected);
    }

    public Access access(ProfileField field) {
        return switch (field.flow()) {
            case ALWAYS, OPTIONAL_FAMILY -> Access.active();
            case UNMARRIED -> familyAccess(Set.of("UNMARRIED"));
            case WITH_FAMILY -> familyAccess(Set.of("SINGLE_PARENT", "COUPLE_OR_ENGAGED"));
            case COUPLE -> familyAccess(Set.of("COUPLE_OR_ENGAGED"));
            case PARENTS_EXCEPTION -> parentsAccess();
        };
    }

    private Access familyAccess(Set<String> types) {
        if (value(FAMILY_TYPE).state() != ValueState.PROVIDED) {
            return Access.waiting(Set.of(FAMILY_TYPE));
        }
        if (types.contains(value(FAMILY_TYPE).value())) {
            return Access.active();
        }
        return Access.notCollected();
    }

    private Access parentsAccess() {
        if (provided(IS_UNIVERSITY_STUDENT, false) || provided(IS_HOUSEHOLD_SEPARATED, false)) {
            return Access.notCollected();
        }
        if (value(FAMILY_TYPE).state() == ValueState.PROVIDED
                && !provided(FAMILY_TYPE, "UNMARRIED")) {
            return Access.notCollected();
        }
        if (value(APPLICANT_MONTHLY_INCOME).value() instanceof Long income && income > 0) {
            return Access.notCollected();
        }
        Set<ProfileField> missing = new LinkedHashSet<>();
        for (ProfileField dependency :
                List.of(
                        FAMILY_TYPE,
                        IS_UNIVERSITY_STUDENT,
                        IS_HOUSEHOLD_SEPARATED,
                        APPLICANT_MONTHLY_INCOME)) {
            if (value(dependency).state() != ValueState.PROVIDED) {
                missing.add(dependency);
            }
        }
        if (!missing.isEmpty()) {
            return Access.waiting(missing);
        }
        return Access.active();
    }

    public void validate(LocalDate today) {
        for (ProfileField field : ProfileField.values()) {
            validateField(field, today);
        }
        validateVehicle(APPLICANT_OWNS_CAR, APPLICANT_CAR_VALUE);
        validateVehicle(FAMILY_OWNS_CAR, FAMILY_CAR_VALUE);
        validateFamilyTotals();
        if (provided(HAS_CHILDREN_OR_PREGNANCY, false)) {
            if (value(CHILDREN_COUNT_INCLUDING_FETUS).value() instanceof Long count && count != 0) {
                throw new InvalidComparisonException("자녀 없음과 자녀 수가 일치하지 않습니다.");
            }
            if (value(YOUNGEST_CHILD).state() == ValueState.PROVIDED) {
                throw new InvalidComparisonException("자녀 없음이면 막내 정보를 입력할 수 없습니다.");
            }
        }
        if (provided(HAS_CHILDREN_OR_PREGNANCY, true)
                && provided(CHILDREN_COUNT_INCLUDING_FETUS, 0L)) {
            throw new InvalidComparisonException("자녀 또는 태아가 있으면 자녀 수는 1 이상이어야 합니다.");
        }
    }

    private void validateFamilyTotals() {
        validateFamilyOwnership(APPLICANT_OWNS_HOUSE, FAMILY_OWNS_HOUSE);
        validateFamilyOwnership(APPLICANT_OWNS_CAR, FAMILY_OWNS_CAR);
        validateFamilyAmount(APPLICANT_CAR_VALUE, FAMILY_CAR_VALUE);
    }

    private void validateFamilyOwnership(ProfileField applicant, ProfileField family) {
        if (provided(applicant, true) && provided(family, false)) {
            throw new InvalidComparisonException("본인 소유 항목은 본인 포함 가족 보유 정보와 일치해야 합니다.");
        }
    }

    private void validateFamilyAmount(ProfileField applicant, ProfileField family) {
        if (value(applicant).value() instanceof Long applicantAmount
                && value(family).value() instanceof Long familyAmount
                && familyAmount < applicantAmount) {
            throw new InvalidComparisonException("본인 포함 가족 금액은 입력한 본인 금액보다 작을 수 없습니다.");
        }
    }

    private void validateField(ProfileField field, LocalDate today) {
        InputValue input = value(field);
        Access access = access(field);
        if (input.state() == ValueState.PROVIDED && access.state() != AccessState.ACTIVE) {
            throw new InvalidComparisonException(field + ": 현재 입력 흐름에서 수집하지 않는 항목입니다.");
        }
        if (input.value() instanceof LocalDate date && date.isAfter(today)) {
            throw new InvalidComparisonException("실제 생년월일은 미래일 수 없습니다.");
        }
        if (field == BIRTH_DATE
                && input.value() instanceof LocalDate date
                && Period.between(date, today).getYears() < 19) {
            throw new InvalidComparisonException("이 입력 기능은 만 19세 이상을 대상으로 합니다.");
        }
        if (input.value() instanceof DateEvent event
                && event.actual()
                && event.date().isAfter(today)) {
            throw new InvalidComparisonException("실제 신고일과 생년월일은 미래일 수 없습니다.");
        }
        if (input.state() == ValueState.NOT_APPLICABLE
                && access.state() == AccessState.ACTIVE
                && !allowsNotApplicable(field)) {
            throw new InvalidComparisonException(field + ": 이 항목은 해당 없음으로 저장할 수 없습니다.");
        }
    }

    private boolean allowsNotApplicable(ProfileField field) {
        if (field == APPLICANT_CAR_VALUE) {
            return provided(APPLICANT_OWNS_CAR, false);
        }
        if (field == FAMILY_CAR_VALUE) {
            return provided(FAMILY_OWNS_CAR, false);
        }
        if (field == YOUNGEST_CHILD || field == CHILDREN_COUNT_INCLUDING_FETUS) {
            return provided(HAS_CHILDREN_OR_PREGNANCY, false);
        }
        return false;
    }

    private void validateVehicle(ProfileField owns, ProfileField price) {
        if (provided(owns, false) && value(price).state() == ValueState.PROVIDED) {
            throw new InvalidComparisonException("자동차 없음과 차량 가격이 일치하지 않습니다.");
        }
        if (provided(owns, true) && value(price).state() == ValueState.NOT_APPLICABLE) {
            throw new InvalidComparisonException("차량이 있으므로 가격은 해당 없음일 수 없습니다.");
        }
    }

    public enum AccessState {
        ACTIVE,
        WAITING,
        NOT_COLLECTED
    }

    public record Access(AccessState state, Set<ProfileField> dependencies) {
        public Access {
            dependencies = Set.copyOf(dependencies);
        }

        public static Access active() {
            return new Access(AccessState.ACTIVE, Set.of());
        }

        public static Access notCollected() {
            return new Access(AccessState.NOT_COLLECTED, Set.of());
        }

        public static Access waiting(Set<ProfileField> dependencies) {
            return new Access(AccessState.WAITING, dependencies);
        }
    }
}
