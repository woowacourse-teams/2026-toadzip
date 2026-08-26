package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

public record LhLeaseCatalogCollectionRequest(int pageSize, int maxPages) {

    public LhLeaseCatalogCollectionRequest {
        if (pageSize < 1 || pageSize > 10_000 || maxPages < 1 || maxPages > 10_000) {
            throw new InvalidIngestRequestException("페이지 크기와 최대 페이지 수는 1~10000이어야 합니다.");
        }
    }

    public String requestDescription(int page) {
        return "PG_SZ=" + pageSize + "&PAGE=" + page;
    }
}
