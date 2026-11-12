package com.toadzip.backend.seo.repository;

/** 시·군·구와 저장된 공급 유형 값별 공개 단지 수. */
public record SeoRegionCount(String provinceCode, String districtCode, String storedRentalType, long complexCount) {
}
