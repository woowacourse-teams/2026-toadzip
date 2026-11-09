package com.toadzip.backend.ingest.enrichment.service;

/** LH 임대 카탈로그의 지역·공급유형·단지명을 제품 단지와 비교할 수 있게 맞춘다. */
final class LhCatalogNames {

    private LhCatalogNames() {
    }

    /** 공백·하이픈·가운뎃점·쉼표·괄호를 지우고 소문자로 바꾼다. */
    static String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-·,()]", "").strip().toLowerCase();
    }

    /** 시도 이름을 약칭으로 줄여 원천 지역명과 제품 단지 주소의 앞부분을 같은 형태로 맞춘다. */
    static String region(String value) {
        return normalized(value)
                .replace("서울특별시", "서울")
                .replace("부산광역시", "부산")
                .replace("대구광역시", "대구")
                .replace("인천광역시", "인천")
                .replace("광주광역시", "광주")
                .replace("대전광역시", "대전")
                .replace("울산광역시", "울산")
                .replace("세종특별자치시", "세종")
                .replace("강원특별자치도", "강원")
                .replace("강원도", "강원")
                .replace("전북특별자치도", "전북")
                .replace("전라북도", "전북")
                .replace("제주특별자치도", "제주")
                .replace("경기도", "경기")
                .replace("충청북도", "충북")
                .replace("충청남도", "충남")
                .replace("전라남도", "전남")
                .replace("경상북도", "경북")
                .replace("경상남도", "경남");
    }
}
