package com.toadzip.backend.housing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class MyHomeRentalHouseUrl {

    private static final String DETAIL_URL =
            "https://www.myhome.go.kr/hws/portal/sch/selectRentalHouseInfoDetail.do";

    // 전국 임대주택 검색폼의 웹 코드다. 공고 API의 공급유형 코드와 다르다.
    private static final Map<String, String> SUPPLY_CODES = Map.ofEntries(
            Map.entry("영구임대", "01"), Map.entry("국민임대", "02"),
            Map.entry("50년임대", "03"), Map.entry("매입임대", "04"),
            Map.entry("10년임대", "05"), Map.entry("5년임대", "06"),
            Map.entry("장기전세", "07"), Map.entry("행복주택", "09"),
            Map.entry("공공기숙사", "10"), Map.entry("통합공공임대", "11"),
            Map.entry("6년임대", "12")
    );

    private MyHomeRentalHouseUrl() {
    }

    static String fromSourceIdentifier(String sourceIdentifier) {
        if (sourceIdentifier == null) {
            return null;
        }
        try {
            List<String> parts = readKeyParts(sourceIdentifier);
            String complexNumber = parts.getFirst();
            String supplyType = parts.get(2);
            if (complexNumber == null || !complexNumber.matches("[1-9][0-9]*")
                    || supplyType == null || Long.parseLong(complexNumber) <= 0) {
                return null;
            }
            String supplyCode = SUPPLY_CODES.get(supplyType);
            if (supplyCode == null) {
                return null;
            }
            String rentalHouseCategory = "C";
            if ("매입임대".equals(supplyType)) {
                rentalHouseCategory = "B";
            }
            return DETAIL_URL + "?hsmpSn=" + complexNumber + "&suplyTy=" + supplyCode
                    + "&rthousSe=" + rentalHouseCategory;
        } catch (IllegalArgumentException invalidSourceIdentifier) {
            return null;
        }
    }

    private static List<String> readKeyParts(String identifier) {
        List<String> parts = new ArrayList<>();
        int offset = 0;
        for (int index = 0; index < 6; index++) {
            int separator = identifier.indexOf(':', offset);
            if (separator < 0) {
                throw new IllegalArgumentException("원천키 길이 구분자가 없습니다.");
            }
            int length = Integer.parseInt(identifier.substring(offset, separator));
            offset = separator + 1;
            if (length == -1) {
                parts.add(null);
                continue;
            }
            if (length < 0 || length > identifier.length() - offset) {
                throw new IllegalArgumentException("원천키 길이가 올바르지 않습니다.");
            }
            parts.add(identifier.substring(offset, offset + length));
            offset += length;
        }
        if (offset != identifier.length()) {
            throw new IllegalArgumentException("원천키에 초과 값이 있습니다.");
        }
        return parts;
    }
}
