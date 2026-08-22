package com.toadzip.backend.ingest.dto;

public record MyHomeNoticeCollectionRequest(int pageSize, int maxPages) {

    public MyHomeNoticeCollectionRequest {
        if (pageSize < 1 || pageSize > 1_000 || maxPages < 1 || maxPages > 1_000) {
            throw new IllegalArgumentException("페이지 크기와 최대 페이지 수는 1~1000이어야 합니다.");
        }
    }

    public String requestDescription(MyHomeNoticeSupplyType supplyType, int page) {
        return "suplyTy=" + supplyType.requestCode() + "&pageNo=" + page + "&numOfRows=" + pageSize;
    }
}
