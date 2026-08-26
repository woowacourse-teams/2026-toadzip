package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

public record MyHomeComplexCollectionRequest(
        String provinceCode,
        String districtCode,
        int pageSize,
        int maxPages
) {

    public MyHomeComplexCollectionRequest {
        validateRegionCodes(provinceCode, districtCode);
        validatePaging(pageSize, maxPages);
    }

    public static MyHomeComplexCollectionRequest allRegions(int pageSize, int maxPages) {
        return new MyHomeComplexCollectionRequest(null, null, pageSize, maxPages);
    }

    public boolean requestsAllRegions() {
        return provinceCode == null;
    }

    public String requestDescription(MyHomeRegion region, int page) {
        return region.requestDescription() + "&pageNo=" + page + "&numOfRows=" + pageSize;
    }

    private static void validateRegionCodes(String provinceCode, String districtCode) {
        if ((provinceCode == null) != (districtCode == null)) {
            throw new InvalidIngestRequestException("시·도 코드와 시·군·구 코드는 함께 입력해야 합니다.");
        }
    }

    private static void validatePaging(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 1_000 || maxPages < 1 || maxPages > 1_000) {
            throw new InvalidIngestRequestException("페이지 크기와 최대 페이지 수는 1~1000이어야 합니다.");
        }
    }
}
