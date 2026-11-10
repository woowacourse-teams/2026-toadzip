package com.toadzip.backend.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.region.repository.RegionSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class EligibilityExtractionDraftServiceTest {
    private static final List<RegionSearchResult> CATALOG = List.of(
            new RegionSearchResult("11140", "서울특별시", "중구", "서울특별시 중구"),
            new RegionSearchResult("26110", "부산광역시", "중구", "부산광역시 중구"),
            new RegionSearchResult("28237", "인천광역시", "서해구", "인천광역시 서해구"),
            new RegionSearchResult("41461", "경기도", "용인시 처인구", "경기도 용인시 처인구"),
            new RegionSearchResult("41463", "경기도", "용인시 기흥구", "경기도 용인시 기흥구"),
            new RegionSearchResult("44150", "충청남도", "공주시", "충청남도 공주시"));

    @Test
    void 공고의_지역_이름은_시도_시군구_코드로_바꾸고_어느_곳인지_모를_이름은_비운다() {
        assertThat(EligibilityExtractionDraftService.regionCodes(CATALOG, "인천광역시")).containsExactly("28");
        assertThat(EligibilityExtractionDraftService.regionCodes(CATALOG, "경기도 용인시")).containsExactly("41461", "41463");
        assertThat(EligibilityExtractionDraftService.regionCodes(CATALOG, "공주시")).containsExactly("44150");
        // 시·도 없이 쓴 중구는 서울·부산 어느 곳인지 알 수 없어 직접 확인으로 둔다.
        assertThat(EligibilityExtractionDraftService.regionCodes(CATALOG, "중구")).isEmpty();
        assertThat(EligibilityExtractionDraftService.regionCodes(CATALOG, "서울특별시 중구")).containsExactly("11140");
    }
}
