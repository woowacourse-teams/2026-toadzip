package com.toadzip.backend.housing.domain;

public enum AgencyCode {
    LH("한국토지주택공사"),
    SH("서울주택도시공사"),
    GH("경기주택도시공사"),
    ETC("기타");

    private final String displayName;

    AgencyCode(String displayName) {
        this.displayName = displayName;
    }

    public static AgencyCode fromStoredValue(String value) {
        return switch (value) {
            case "LH", "한국토지주택공사" -> LH;
            case "SH", "서울주택도시공사" -> SH;
            case "GH", "경기주택도시공사" -> GH;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 공급기관 코드다.");
        };
    }

    public String displayName() {
        return displayName;
    }
}
