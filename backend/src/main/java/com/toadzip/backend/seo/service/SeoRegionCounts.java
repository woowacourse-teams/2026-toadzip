package com.toadzip.backend.seo.service;

import com.toadzip.backend.housing.service.HousingComplexCodeMapper;
import com.toadzip.backend.seo.repository.SeoRegionCount;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** 지역 페이지 단위(지역, 지역+공급 유형)의 공개 단지 수. */
final class SeoRegionCounts {

    /** 이 수 이상인 지역 페이지만 색인하고 사이트맵에 넣는다. */
    static final long INDEXABLE_MINIMUM = 3;

    private final Map<SeoRegionPage, Long> counts = new TreeMap<>();

    SeoRegionCounts(List<SeoRegionCount> rows, HousingComplexCodeMapper codeMapper) {
        rows.forEach(row -> add(row, codeMapper.toRentalType(row.storedRentalType())));
    }

    private void add(SeoRegionCount row, String rentalType) {
        String district = row.districtCode();
        String province = district.substring(0, 2);
        increase(new SeoRegionPage(province, null), row.complexCount());
        increase(new SeoRegionPage(district, null), row.complexCount());
        if (!SeoLabels.RENTAL_TYPES.containsKey(rentalType)) {
            return;
        }
        increase(new SeoRegionPage(province, rentalType), row.complexCount());
        increase(new SeoRegionPage(district, rentalType), row.complexCount());
    }

    private void increase(SeoRegionPage page, long count) {
        counts.merge(page, count, Long::sum);
    }

    long count(SeoRegionPage page) {
        return counts.getOrDefault(page, 0L);
    }

    /** 시·도 페이지에는 시·군·구를, 시·군·구 페이지에는 공급 유형별 페이지를 하위로 둔다. */
    List<SeoRegionPage> children(SeoRegionPage parent) {
        return counts.keySet().stream()
                .filter(parent::isParentOf)
                .toList();
    }

    /** 지역 페이지 색인 기준. 페이지의 robots와 지역 사이트맵이 같은 기준을 쓴다. 기타 유형은 색인하지 않는다. */
    boolean indexable(SeoRegionPage page) {
        return count(page) >= INDEXABLE_MINIMUM && !"ETC".equals(page.rentalType());
    }

    /** 이름을 아는(현재 코드의) 지역 중 색인 기준을 넘는 페이지. */
    List<SeoRegionPage> indexablePages(Predicate<String> knownRegion) {
        return counts.keySet().stream()
                .filter(page -> knownRegion.test(page.regionCode()))
                .filter(this::indexable)
                .toList();
    }
}
