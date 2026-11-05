package com.toadzip.backend.eligibility;

import static com.toadzip.backend.eligibility.domain.InputFact.*;

import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ValueState;

import java.util.EnumMap;
import java.util.Map;

/**
 * The three profiles are fictional, contain no real users' personal data, and use only the approved
 * input list.
 */
public final class ProfileFixtures {
    private ProfileFixtures() {}

    public static EligibilityProfile b() {
        Map<InputFact, InputValue> facts =
                facts(
                        BIRTH_DATE,
                        "1994-05-20",
                        IS_UNIVERSITY_STUDENT,
                        false,
                        IS_JOB_SEEKER,
                        false,
                        RESIDENCE_REGION,
                        "11440",
                        APPLICANT_MONTHLY_INCOME,
                        4800000,
                        APPLICANT_OWNS_HOUSE,
                        false,
                        APPLICANT_OWNS_CAR,
                        true,
                        APPLICANT_CAR_VALUE,
                        30000000,
                        FAMILY_TYPE,
                        "COUPLE_OR_ENGAGED",
                        FAMILY_MEMBER_COUNT,
                        3,
                        MARRIAGE_REPORT_DATE,
                        "2023-09-02",
                        IS_DUAL_INCOME,
                        true,
                        HAS_CHILDREN,
                        true,
                        CHILDREN_COUNT,
                        1,
                        YOUNGEST_CHILD_BIRTH_DATE,
                        "2025-03-10",
                        HOUSEHOLD_MONTHLY_INCOME,
                        9500000,
                        HOUSEHOLD_HAS_HOUSE_OWNER,
                        false,
                        HOUSEHOLD_HAS_CAR_OWNER,
                        true,
                        HOUSEHOLD_CAR_VALUE,
                        30000000,
                        INCOME_BASE_REGION,
                        "11140");
        absent(
                facts,
                UNIVERSITY_REGION,
                SAVINGS_PAYMENT_COUNT,
                SAVINGS_PAID_AMOUNT,
                SAVINGS_PERIOD);
        specials(facts, false);
        return new EligibilityProfile(null, 0, null, facts);
    }

    public static EligibilityProfile a() {
        Map<InputFact, InputValue> facts =
                facts(
                        BIRTH_DATE,
                        "1995-03-02",
                        FAMILY_TYPE,
                        "UNMARRIED",
                        HAS_CHILDREN,
                        false,
                        FAMILY_MEMBER_COUNT,
                        3,
                        RESIDENCE_REGION,
                        "41597",
                        INCOME_BASE_REGION,
                        "41117",
                        APPLICANT_MONTHLY_INCOME,
                        3200000,
                        APPLICANT_ASSETS,
                        50000000,
                        APPLICANT_OWNS_HOUSE,
                        false,
                        APPLICANT_OWNS_CAR,
                        false,
                        HOUSEHOLD_MONTHLY_INCOME,
                        9000000,
                        HOUSEHOLD_ASSETS,
                        400000000,
                        HOUSEHOLD_HAS_HOUSE_OWNER,
                        true,
                        HOUSEHOLD_HAS_CAR_OWNER,
                        true,
                        HOUSEHOLD_CAR_VALUE,
                        25000000,
                        SAVINGS_PAYMENT_COUNT,
                        40,
                        SAVINGS_PAID_AMOUNT,
                        4000000,
                        SAVINGS_PERIOD,
                        48);
        absent(
                facts,
                MARRIAGE_REPORT_DATE,
                IS_DUAL_INCOME,
                CHILDREN_COUNT,
                YOUNGEST_CHILD_BIRTH_DATE,
                APPLICANT_CAR_VALUE);
        return new EligibilityProfile(null, 0, null, facts);
    }

    public static EligibilityProfile c() {
        Map<InputFact, InputValue> facts =
                facts(
                        BIRTH_DATE,
                        "1958-03-15",
                        IS_UNIVERSITY_STUDENT,
                        false,
                        IS_JOB_SEEKER,
                        false,
                        RESIDENCE_REGION,
                        "48240",
                        LEASE_END_DATE,
                        "2027-05-31",
                        APPLICANT_MONTHLY_INCOME,
                        600000,
                        APPLICANT_ASSETS,
                        15000000,
                        APPLICANT_OWNS_HOUSE,
                        false,
                        APPLICANT_OWNS_CAR,
                        false,
                        FAMILY_TYPE,
                        "UNMARRIED",
                        FAMILY_MEMBER_COUNT,
                        1,
                        HAS_CHILDREN,
                        false,
                        HOUSEHOLD_MONTHLY_INCOME,
                        600000,
                        HOUSEHOLD_ASSETS,
                        15000000,
                        HOUSEHOLD_HAS_HOUSE_OWNER,
                        false,
                        HOUSEHOLD_HAS_CAR_OWNER,
                        false,
                        PARENTS_COUNT,
                        0);
        absent(
                facts,
                APPLICANT_CAR_VALUE,
                MARRIAGE_REPORT_DATE,
                IS_DUAL_INCOME,
                CHILDREN_COUNT,
                YOUNGEST_CHILD_BIRTH_DATE,
                HOUSEHOLD_CAR_VALUE,
                UNIVERSITY_REGION,
                PARENTS_MONTHLY_INCOME,
                PARENTS_ASSETS,
                PARENTS_CAR_VALUE);
        specials(facts, true);
        return new EligibilityProfile(null, 0, null, facts);
    }

    public static EligibilityProfile with(
            EligibilityProfile original, InputFact fact, Object value) {
        Map<InputFact, InputValue> facts = new EnumMap<>(original.facts());
        facts.put(fact, new InputValue(ValueState.PROVIDED, value));
        return new EligibilityProfile(null, 0, null, facts);
    }

    private static Map<InputFact, InputValue> facts(Object... entries) {
        Map<InputFact, InputValue> facts = new EnumMap<>(InputFact.class);
        for (int index = 0; index < entries.length; index += 2) {
            facts.put(
                    (InputFact) entries[index],
                    new InputValue(ValueState.PROVIDED, entries[index + 1]));
        }
        return facts;
    }

    private static void absent(Map<InputFact, InputValue> facts, InputFact... keys) {
        for (InputFact key : keys) {
            facts.put(key, new InputValue(ValueState.NOT_APPLICABLE, null));
        }
    }

    private static void specials(Map<InputFact, InputValue> facts, boolean nearPoverty) {
        facts.put(BASIC_LIVELIHOOD_RECIPIENT, new InputValue(ValueState.PROVIDED, false));
        facts.put(NEAR_POVERTY, new InputValue(ValueState.PROVIDED, nearPoverty));
        facts.put(SUPPORTED_SINGLE_PARENT_FAMILY, new InputValue(ValueState.PROVIDED, false));
        facts.put(VETERAN_TARGET, new InputValue(ValueState.PROVIDED, false));
    }
}
