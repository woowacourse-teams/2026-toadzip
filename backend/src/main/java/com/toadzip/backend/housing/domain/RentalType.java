package com.toadzip.backend.housing.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum RentalType implements LegacyStoredValue {
    HAPPY_HOUSING("행복주택"),
    NATIONAL_RENTAL("국민임대"),
    PERMANENT_RENTAL("영구임대"),
    /** 분양전환 공공임대(5·10년). LH 원천이 둘을 구분하지 않아 하나로 둔다. */
    PUBLIC_RENTAL("공공임대"),
    PUBLIC_RENTAL_50Y("50년공공임대"),
    INTEGRATED_PUBLIC_RENTAL("통합공공임대"),
    REDEVELOPMENT_RENTAL("재개발임대"),
    LONG_TERM_JEONSE("장기전세"),
    ETC("기타");

    private final String legacyStoredValue;

    RentalType(String legacyStoredValue) {
        this.legacyStoredValue = legacyStoredValue;
    }

    public static RentalType fromStoredValue(String value) {
        return switch (value) {
            case "HAPPY_HOUSING", "행복주택" -> HAPPY_HOUSING;
            case "NATIONAL_RENTAL", "국민임대" -> NATIONAL_RENTAL;
            case "PERMANENT_RENTAL", "영구임대" -> PERMANENT_RENTAL;
            case "PUBLIC_RENTAL", "공공임대", "PUBLIC_RENTAL_5Y", "5년임대", "PUBLIC_RENTAL_10Y", "10년임대" -> PUBLIC_RENTAL;
            case "PUBLIC_RENTAL_50Y", "50년공공임대" -> PUBLIC_RENTAL_50Y;
            case "INTEGRATED_PUBLIC_RENTAL", "통합공공임대" -> INTEGRATED_PUBLIC_RENTAL;
            case "REDEVELOPMENT_RENTAL", "재개발임대" -> REDEVELOPMENT_RENTAL;
            case "LONG_TERM_JEONSE", "장기전세" -> LONG_TERM_JEONSE;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 임대유형 코드다.");
        };
    }

    /** 요청의 공급유형 코드. 합치기 전 공공임대 5·10년 코드도 공공임대로 받는다. */
    @JsonCreator
    public static RentalType fromCode(String code) {
        return switch (code) {
            case "PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_10Y" -> PUBLIC_RENTAL;
            default -> valueOf(code);
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
