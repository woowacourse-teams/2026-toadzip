package com.toadzip.backend.seo.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 화면과 같은 한국어 이름. 프론트 searchFilterOptions·announcementDetailPresentation의 이름을 따른다. */
final class SeoLabels {

    /** 지역·공급 유형 페이지를 여는 공급 유형. 프론트 지도 필터가 받는 값과 같다. */
    static final Map<String, String> RENTAL_TYPES = rentalTypes();

    private static final Map<String, String> APPLICATION_STATUSES = Map.of(
            "BEFORE_APPLICATION", "공고중",
            "APPLYING", "접수중",
            "CONDITIONAL", "조건부 접수",
            "CLOSED", "접수마감",
            "CANCELLED", "공고취소");

    private static final Map<String, String> RECRUITMENT_TYPES = Map.of(
            "NEW", "입주자",
            "WAITLIST", "예비입주자");

    private static final Map<String, String> PROVINCE_SHORT_NAMES = Map.ofEntries(
            Map.entry("서울특별시", "서울"), Map.entry("부산광역시", "부산"), Map.entry("대구광역시", "대구"),
            Map.entry("인천광역시", "인천"), Map.entry("광주광역시", "광주"), Map.entry("대전광역시", "대전"),
            Map.entry("울산광역시", "울산"), Map.entry("세종특별자치시", "세종"), Map.entry("경기도", "경기"),
            Map.entry("강원특별자치도", "강원"), Map.entry("충청북도", "충북"), Map.entry("충청남도", "충남"),
            Map.entry("전북특별자치도", "전북"), Map.entry("전라남도", "전남"), Map.entry("경상북도", "경북"),
            Map.entry("경상남도", "경남"), Map.entry("제주특별자치도", "제주"));

    private SeoLabels() {
    }

    private static Map<String, String> rentalTypes() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("HAPPY_HOUSING", "행복주택");
        labels.put("NATIONAL_RENTAL", "국민임대");
        labels.put("PERMANENT_RENTAL", "영구임대");
        labels.put("PUBLIC_RENTAL", "분양전환 공공임대");
        labels.put("PUBLIC_RENTAL_50Y", "50년 공공임대");
        labels.put("INTEGRATED_PUBLIC_RENTAL", "통합공공임대");
        labels.put("REDEVELOPMENT_RENTAL", "재개발임대");
        labels.put("ETC", "기타");
        return Collections.unmodifiableMap(labels);
    }

    static Optional<String> rentalType(String code) {
        return Optional.ofNullable(code).map(RENTAL_TYPES::get);
    }

    static Optional<String> applicationStatus(String code) {
        return Optional.ofNullable(code).map(APPLICATION_STATUSES::get);
    }

    static Optional<String> recruitmentType(String code) {
        return Optional.ofNullable(code).map(RECRUITMENT_TYPES::get);
    }

    static String rentalTypeSlug(String code) {
        return code.toLowerCase().replace('_', '-');
    }

    static Optional<String> rentalTypeFromSlug(String slug) {
        return RENTAL_TYPES.keySet().stream()
                .filter(code -> rentalTypeSlug(code).equals(slug))
                .findFirst();
    }

    /** "인천광역시 남동구" → "인천 남동구". */
    static String shortRegionName(String regionName) {
        int space = regionName.indexOf(' ');
        if (space < 0) {
            return PROVINCE_SHORT_NAMES.getOrDefault(regionName, regionName);
        }
        String province = regionName.substring(0, space);
        return PROVINCE_SHORT_NAMES.getOrDefault(province, province) + regionName.substring(space);
    }
}
