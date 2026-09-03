package com.toadzip.backend.region.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.region.dto.response.RegionSearchItemResponse;
import com.toadzip.backend.region.dto.response.RegionSearchResponse;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionQueryServiceTest {

    @Test
    void 검색어를_공백없이_정규화하고_저장소_결과의_모든_필드와_순서를_응답에_보존한다() {
        RegionQueryService service = new RegionQueryService(new SeoulOnlyRegionSearchRepository());

        RegionSearchResponse response = service.searchRegions("  서울  ");

        assertEquals(List.of(
                new RegionSearchItemResponse("11", "서울특별시", null, "서울특별시 전체"),
                new RegionSearchItemResponse("11110", "서울특별시", "종로구", "서울특별시 종로구")
        ), response.items());
    }

    @Test
    void 검색_응답의_항목_목록은_수정할수_없다() {
        RegionQueryService service = new RegionQueryService(new SeoulOnlyRegionSearchRepository());

        RegionSearchResponse response = service.searchRegions("서울");

        assertThrows(UnsupportedOperationException.class, () -> response.items().add(
                new RegionSearchItemResponse("99999", "테스트", "테스트", "테스트")
        ));
    }

    @Test
    void 검색어의_연속_공백을_하나로_정규화한다() {
        RecordingRegionSearchRepository repository = new RecordingRegionSearchRepository();
        RegionQueryService service = new RegionQueryService(repository);

        service.searchRegions("  서울    중구  ");

        assertEquals("서울 중구", repository.keyword);
    }

    private static class SeoulOnlyRegionSearchRepository implements RegionSearchRepository {

        @Override
        public List<RegionSearchResult> findByKeyword(String normalizedKeyword) {
            if (normalizedKeyword.equals("서울")) {
                return List.of(
                        new RegionSearchResult("11", "서울특별시", null, "서울특별시 전체"),
                        new RegionSearchResult("11110", "서울특별시", "종로구", "서울특별시 종로구")
                );
            }
            return List.of();
        }
    }

    private static class RecordingRegionSearchRepository implements RegionSearchRepository {

        private String keyword;

        @Override
        public List<RegionSearchResult> findByKeyword(String normalizedKeyword) {
            keyword = normalizedKeyword;
            return List.of();
        }
    }
}
