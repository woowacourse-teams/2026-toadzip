package com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.api;

import static com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.LhLeaseCatalogCollectionRequest.MAX_PAGES;
import static com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.LhLeaseCatalogCollectionRequest.MAX_PAGE_SIZE;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

public record LhLeaseCatalogCollectionCommand(int pageSize, int maxPages) {

    public LhLeaseCatalogCollectionCommand {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new InvalidIngestRequestException(
                    "페이지 크기는 1~" + MAX_PAGE_SIZE + ", 최대 페이지 수는 1~" + MAX_PAGES + "이어야 합니다.");
        }
    }
}
