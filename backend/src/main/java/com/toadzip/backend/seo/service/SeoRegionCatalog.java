package com.toadzip.backend.seo.service;

import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 지역 페이지를 여는 시·도(2자리)·시·군·구(5자리) 코드와 이름. */
@Component
class SeoRegionCatalog {

    private final Map<String, String> namesByCode = new HashMap<>();
    private final Map<String, String> codesByDisplayName = new HashMap<>();

    SeoRegionCatalog(RegionSearchRepository regionSearchRepository) {
        regionSearchRepository.findByKeyword("").forEach(this::add);
    }

    private void add(RegionSearchResult region) {
        String code = region.regionCode();
        if (code.length() == 2) {
            namesByCode.put(code, region.provinceName());
            return;
        }
        if (code.length() == 5) {
            namesByCode.put(code, region.displayName());
            codesByDisplayName.put(region.displayName(), code);
        }
    }

    Optional<String> name(String regionCode) {
        return Optional.ofNullable(namesByCode.get(regionCode));
    }

    /** 단지 상세의 지역 이름("서울특별시 중구")으로 시·군·구 코드를 찾는다. */
    Optional<String> districtCode(String displayName) {
        return Optional.ofNullable(displayName).map(codesByDisplayName::get);
    }
}
