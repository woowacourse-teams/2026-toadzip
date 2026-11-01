package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.RentalType;
import java.util.Map;
import java.util.Optional;

/**
 * 마이홈 단지 원천 식별자(`hsmpSn:코드`)의 공급유형 코드. 식별자가 이미 저장되어 있어 공공임대 5·10년을 하나로 합친 뒤에도
 * 원천 코드는 바꾸지 않고, 제품 공급유형으로 옮길 때만 합친다.
 */
public final class MyHomeComplexSourceCode {

    private static final Map<String, String> CODES = Map.of(
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

    private MyHomeComplexSourceCode() {
    }

    /** 마이홈 `suplyTyNm`의 원천 코드. 건설임대가 아닌 유형은 비어 있다. */
    public static Optional<String> of(String supplyTypeName) {
        return Optional.ofNullable(supplyTypeName).map(CODES::get);
    }

    public static boolean known(String code) {
        return CODES.containsValue(code);
    }

    /** 원천 코드의 제품 공급유형. */
    static String productType(String code) {
        return RentalType.fromStoredValue(code).name();
    }
}
