package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.lh.collection.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.util.Set;

class MyHomeComplexClassificationPolicy {

    /** 원천 식별자에 쓰는 공급유형 코드. */
    String sourceCode(String value) {
        return MyHomeComplexSourceCode.of(value)
                .orElseThrow(() -> invalid("지원하지 않는 건설임대 공급유형입니다: " + value));
    }

    String supplyType(String value) {
        return MyHomeComplexSourceCode.productType(sourceCode(value));
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
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}
