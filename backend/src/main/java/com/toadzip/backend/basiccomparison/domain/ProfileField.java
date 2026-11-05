package com.toadzip.backend.basiccomparison.domain;

/** Closed current input contract. Uncollected qualifications cannot be expressed as fields. */
public enum ProfileField {
    BIRTH_DATE(Type.DATE, Flow.ALWAYS, "생년월일", "DATE"),
    RESIDENCE_REGION(Type.REGION, Flow.ALWAYS, "거주지역", "REGION_CODE"),
    IS_UNIVERSITY_STUDENT(Type.BOOLEAN, Flow.ALWAYS, "대학생 여부", "BOOLEAN"),
    IS_JOB_SEEKER(Type.BOOLEAN, Flow.ALWAYS, "취업준비생 여부", "BOOLEAN"),
    APPLICANT_OWNS_HOUSE(Type.BOOLEAN, Flow.ALWAYS, "본인 주택 소유 여부", "BOOLEAN"),
    APPLICANT_OWNS_CAR(Type.BOOLEAN, Flow.ALWAYS, "본인 자동차 소유 여부", "BOOLEAN"),
    APPLICANT_CAR_VALUE(Type.MONEY, Flow.ALWAYS, "본인 차량 가격", "KRW"),
    APPLICANT_ASSETS(Type.MONEY, Flow.ALWAYS, "본인 자산", "KRW"),
    APPLICANT_MONTHLY_INCOME(Type.MONEY, Flow.ALWAYS, "본인 세전 월평균소득", "KRW_PER_MONTH"),
    FAMILY_TYPE(Type.FAMILY_TYPE, Flow.ALWAYS, "가족 형태", "CODE"),
    REGISTERED_FAMILY_COUNT(Type.COUNT, Flow.ALWAYS, "본인 포함 등본상 가족 수", "PEOPLE"),
    HOUSEHOLD_MONTHLY_INCOME(Type.MONEY, Flow.ALWAYS, "세대 합산 세전 월평균소득", "KRW_PER_MONTH"),
    IS_HOUSEHOLD_SEPARATED(Type.BOOLEAN, Flow.UNMARRIED, "세대분리 여부", "BOOLEAN"),
    HAS_CHILDREN_OR_PREGNANCY(Type.BOOLEAN, Flow.WITH_FAMILY, "자녀 또는 임신 여부", "BOOLEAN"),
    CHILDREN_COUNT_INCLUDING_FETUS(Type.COUNT, Flow.WITH_FAMILY, "태아 포함 자녀 수", "PEOPLE"),
    YOUNGEST_CHILD(Type.CHILD_DATE, Flow.WITH_FAMILY, "막내 생년월일 또는 출산예정일", "DATE_EVENT"),
    MARRIAGE_EVENT(Type.MARRIAGE_DATE, Flow.COUPLE, "혼인신고일 또는 예정일", "DATE_EVENT"),
    IS_DUAL_INCOME(Type.BOOLEAN, Flow.COUPLE, "맞벌이 여부", "BOOLEAN"),
    FAMILY_OWNS_HOUSE(Type.BOOLEAN, Flow.OPTIONAL_FAMILY, "본인 포함 가족 주택 소유 여부", "BOOLEAN"),
    FAMILY_OWNS_CAR(Type.BOOLEAN, Flow.OPTIONAL_FAMILY, "본인 포함 가족 자동차 소유 여부", "BOOLEAN"),
    FAMILY_CAR_VALUE(Type.MONEY, Flow.OPTIONAL_FAMILY, "가족 차량 중 최고 가액", "KRW"),
    FAMILY_TOTAL_ASSETS(Type.MONEY, Flow.OPTIONAL_FAMILY, "본인 포함 가족 총자산", "KRW"),
    PARENTS_MONTHLY_INCOME(Type.MONEY, Flow.PARENTS_EXCEPTION, "부모님 소득 합계", "KRW_PER_MONTH");

    private final Type type;
    private final Flow flow;
    private final String label;
    private final String unit;

    ProfileField(Type type, Flow flow, String label, String unit) {
        this.type = type;
        this.flow = flow;
        this.label = label;
        this.unit = unit;
    }

    public Type type() {
        return type;
    }

    public Flow flow() {
        return flow;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    public boolean optional() {
        return flow == Flow.OPTIONAL_FAMILY;
    }

    public enum Type {
        BOOLEAN,
        DATE,
        REGION,
        MONEY,
        COUNT,
        FAMILY_TYPE,
        CHILD_DATE,
        MARRIAGE_DATE
    }

    public enum Flow {
        ALWAYS,
        UNMARRIED,
        WITH_FAMILY,
        COUPLE,
        OPTIONAL_FAMILY,
        PARENTS_EXCEPTION
    }
}
