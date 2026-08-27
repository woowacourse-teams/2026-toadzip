package com.toadzip.backend.housing.service;

import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.dto.response.AgencyResponse;

@Component
public class HousingComplexCodeMapper {

    public String toRentalType(String storedValue) {
        return switch (storedValue) {
            case "HAPPY_HOUSING", "행복주택" -> "HAPPY_HOUSING";
            case "NATIONAL_RENTAL", "국민임대" -> "NATIONAL_RENTAL";
            case "PERMANENT_RENTAL", "영구임대" -> "PERMANENT_RENTAL";
            case "PUBLIC_RENTAL_50Y", "50년공공임대" -> "PUBLIC_RENTAL_50Y";
            case "INTEGRATED_PUBLIC_RENTAL", "통합공공임대" -> "INTEGRATED_PUBLIC_RENTAL";
            case "REDEVELOPMENT_RENTAL", "재개발임대" -> "REDEVELOPMENT_RENTAL";
            case "ETC", "기타" -> "ETC";
            default -> throw new IllegalStateException("지원하지 않는 공급유형 저장값이다.");
        };
    }

    public AgencyResponse toAgency(String storedValue) {
        return switch (storedValue) {
            case "LH", "한국토지주택공사" -> new AgencyResponse("LH", "한국토지주택공사");
            case "SH", "서울주택도시공사" -> new AgencyResponse("SH", "서울주택도시공사");
            case "GH", "경기주택도시공사" -> new AgencyResponse("GH", "경기주택도시공사");
            case "ETC", "기타" -> new AgencyResponse("ETC", "기타");
            default -> throw new IllegalStateException("지원하지 않는 공급기관 저장값이다.");
        };
    }

    public String toPublicationType(String storedValue) {
        return switch (storedValue) {
            case "ORIGINAL", "원공고" -> "ORIGINAL";
            case "CORRECTION", "정정공고" -> "CORRECTION";
            default -> throw new IllegalStateException("지원하지 않는 공고구분 저장값이다.");
        };
    }
}
