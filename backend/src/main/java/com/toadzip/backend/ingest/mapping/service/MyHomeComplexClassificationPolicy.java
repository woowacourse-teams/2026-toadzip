package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.domain.LhProviderPolicy;
import java.util.Map;
import java.util.Set;

class MyHomeComplexClassificationPolicy {

    private static final Map<String, String> SUPPLY_TYPE_CODES = Map.of(
            "행복주택", "HAPPY_HOUSING",
            "국민임대", "NATIONAL_RENTAL",
            "영구임대", "PERMANENT_RENTAL",
            "5년임대", "PUBLIC_RENTAL_5Y",
            "10년임대", "PUBLIC_RENTAL_10Y",
            "50년임대", "PUBLIC_RENTAL_50Y",
            "장기전세", "LONG_TERM_JEONSE",
            "통합공공임대", "INTEGRATED_PUBLIC_RENTAL",
            "재개발임대", "REDEVELOPMENT_RENTAL",
            "기타", "ETC"
    );

    String supplyType(String value) {
        String code = SUPPLY_TYPE_CODES.get(value);
        if (code == null) {
            throw invalid("지원하지 않는 건설임대 공급유형입니다: " + value);
        }
        return code;
    }

    String provider(String value) {
        if (LhProviderPolicy.isLh(value)) {
            return "LH";
        }
        if (Set.of("SH공사", "서울주택도시공사").contains(value)) {
            return "SH";
        }
        if (value.equals("경기주택도시공사")) {
            return "GH";
        }
        return "ETC";
    }

    String heatingType(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("개별")) {
            return "INDIVIDUAL";
        }
        if (value.startsWith("중앙")) {
            return "CENTRAL";
        }
        if (value.startsWith("지역")) {
            return "DISTRICT";
        }
        return "ETC";
    }

    String housingType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "아파트" -> "APARTMENT";
            case "오피스텔" -> "OFFICETEL";
            default -> "ETC";
        };
    }

    String corridorType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "계단식" -> "STAIR";
            case "복도식" -> "CORRIDOR";
            case "혼합식" -> "MIXED";
            case "미상" -> "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    Boolean elevatorInstalled(String value) {
        if (value == null) {
            return null;
        }
        if (Set.of("전체동 설치", "일부동 설치", "설치", "Y", "예").contains(value)) {
            return true;
        }
        if (Set.of("미설치", "N", "아니오").contains(value)) {
            return false;
        }
        throw invalid("승강기 설치 여부 값이 올바르지 않습니다.");
    }

    private MyHomeComplexMappingRejectedException invalid(String detail) {
        return new MyHomeComplexMappingRejectedException(
                com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}
