package com.toadzip.backend.eligibility.domain;

public enum InputFact {
    BIRTH_DATE("birth_date", ValueType.DATE, false, false),
    IS_UNIVERSITY_STUDENT("is_university_student", ValueType.BOOLEAN, false, false),
    IS_JOB_SEEKER("is_job_seeker", ValueType.BOOLEAN, false, false),
    RESIDENCE_REGION("residence_region_code", ValueType.REGION, false, false),
    LEASE_END_DATE("lease_end_date", ValueType.DATE, true, false),
    APPLICANT_MONTHLY_INCOME("monthly_income_krw", ValueType.KRW, false, false),
    APPLICANT_ASSETS("assets_krw", ValueType.KRW, false, false),
    APPLICANT_OWNS_HOUSE("owns_house", ValueType.BOOLEAN, false, false),
    APPLICANT_OWNS_CAR("owns_car", ValueType.BOOLEAN, false, false),
    APPLICANT_CAR_VALUE("car_value_krw", ValueType.KRW, true, false),
    FAMILY_TYPE("family_type", ValueType.CODE, false, false),
    FAMILY_MEMBER_COUNT("family_member_count", ValueType.INTEGER, false, false),
    MARRIAGE_REPORT_DATE("marriage_report_date", ValueType.DATE, true, false),
    IS_DUAL_INCOME("is_dual_income", ValueType.BOOLEAN, true, false),
    HAS_CHILDREN("has_children", ValueType.BOOLEAN, false, false),
    CHILDREN_COUNT("children_count", ValueType.INTEGER, true, false),
    YOUNGEST_CHILD_BIRTH_DATE("youngest_child_birth_date", ValueType.DATE, true, false),
    HOUSEHOLD_MONTHLY_INCOME("household_monthly_income_krw", ValueType.KRW, false, false),
    HOUSEHOLD_ASSETS("household_assets_krw", ValueType.KRW, false, false),
    HOUSEHOLD_HAS_HOUSE_OWNER("household_has_house_owner", ValueType.BOOLEAN, false, false),
    HOUSEHOLD_HAS_CAR_OWNER("household_has_car_owner", ValueType.BOOLEAN, false, false),
    HOUSEHOLD_CAR_VALUE("household_car_value_krw", ValueType.KRW, true, false),
    UNIVERSITY_REGION("university_region_code", ValueType.REGION, true, false),
    INCOME_BASE_REGION("income_base_region_code", ValueType.REGION, true, false),
    SAVINGS_PAYMENT_COUNT("savings_payment_count", ValueType.INTEGER, true, false),
    SAVINGS_PAID_AMOUNT("savings_paid_amount_krw", ValueType.KRW, true, false),
    SAVINGS_PERIOD("savings_period_months", ValueType.MONTHS, true, false),
    PARENTS_MONTHLY_INCOME("parents_monthly_income_krw", ValueType.KRW, true, false),
    PARENTS_ASSETS("parents_assets_krw", ValueType.KRW, true, false),
    PARENTS_CAR_VALUE("parents_car_value_krw", ValueType.KRW, true, false),
    PARENTS_COUNT("parents_count", ValueType.INTEGER, false, false),
    BASIC_LIVELIHOOD_RECIPIENT("BASIC_LIVELIHOOD_RECIPIENT", ValueType.BOOLEAN, false, true),
    NEAR_POVERTY("NEAR_POVERTY", ValueType.BOOLEAN, false, true),
    SUPPORTED_SINGLE_PARENT_FAMILY(
            "SUPPORTED_SINGLE_PARENT_FAMILY", ValueType.BOOLEAN, false, true),
    VETERAN_TARGET("VETERAN_TARGET", ValueType.BOOLEAN, false, true);

    private final String column;
    private final ValueType valueType;
    private final boolean allowsNotApplicable;
    private final boolean special;

    InputFact(String column, ValueType valueType, boolean allowsNotApplicable, boolean special) {
        this.column = column;
        this.valueType = valueType;
        this.allowsNotApplicable = allowsNotApplicable;
        this.special = special;
    }

    public String column() {
        return column;
    }

    public ValueType valueType() {
        return valueType;
    }

    public boolean allowsNotApplicable() {
        return allowsNotApplicable;
    }

    public boolean special() {
        return special;
    }

    public enum ValueType {
        DATE,
        BOOLEAN,
        INTEGER,
        KRW,
        REGION,
        CODE,
        MONTHS
    }
}
