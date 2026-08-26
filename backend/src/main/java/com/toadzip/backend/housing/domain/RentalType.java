package com.toadzip.backend.housing.domain;

import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum RentalType implements LegacyStoredValue {
    HAPPY_HOUSING("행복주택"),
    NATIONAL_RENTAL("국민임대"),
    PERMANENT_RENTAL("영구임대"),
    PUBLIC_RENTAL_50Y("50년공공임대"),
    INTEGRATED_PUBLIC_RENTAL("통합공공임대"),
    REDEVELOPMENT_RENTAL("재개발임대"),
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
            case "PUBLIC_RENTAL_50Y", "50년공공임대" -> PUBLIC_RENTAL_50Y;
            case "INTEGRATED_PUBLIC_RENTAL", "통합공공임대" -> INTEGRATED_PUBLIC_RENTAL;
            case "REDEVELOPMENT_RENTAL", "재개발임대" -> REDEVELOPMENT_RENTAL;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 임대유형 코드다.");
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
