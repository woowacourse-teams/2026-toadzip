package com.toadzip.backend.seo.service;

import java.util.Comparator;
import java.util.Objects;

/** 지역 목록 페이지 하나. rentalType이 null이면 모든 공급 유형이다. */
record SeoRegionPage(String regionCode, String rentalType) implements Comparable<SeoRegionPage> {

    private static final Comparator<SeoRegionPage> ORDER = Comparator.comparing(SeoRegionPage::regionCode)
            .thenComparing(SeoRegionPage::rentalType, Comparator.nullsFirst(Comparator.naturalOrder()));

    String path() {
        if (rentalType == null) {
            return "/regions/" + regionCode;
        }
        return "/regions/" + regionCode + "/" + SeoLabels.rentalTypeSlug(rentalType);
    }

    boolean isParentOf(SeoRegionPage other) {
        if (rentalType != null) {
            return false;
        }
        if (regionCode.length() == 2) {
            return other.rentalType == null && other.regionCode.length() == 5
                    && other.regionCode.startsWith(regionCode);
        }
        return other.rentalType != null && Objects.equals(other.regionCode, regionCode);
    }

    @Override
    public int compareTo(SeoRegionPage other) {
        return ORDER.compare(this, other);
    }
}
