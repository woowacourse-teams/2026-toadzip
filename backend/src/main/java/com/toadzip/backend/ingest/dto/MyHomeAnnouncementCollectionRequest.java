package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

public record MyHomeAnnouncementCollectionRequest(int pageSize, int maxPages) {

    public MyHomeAnnouncementCollectionRequest {
        if (pageSize < 1 || pageSize > 1_000 || maxPages < 1 || maxPages > 1_000) {
            throw new InvalidIngestRequestException(
                    "페이지 크기와 최대 페이지 수는 1~1000이어야 합니다."
            );
        }
    }

    public String requestDescription(MyHomeAnnouncementSupplyType supplyType, int page) {
        return "suplyTy=" + supplyType.requestCode() + "&pageNo=" + page + "&numOfRows=" + pageSize;
    }
}
