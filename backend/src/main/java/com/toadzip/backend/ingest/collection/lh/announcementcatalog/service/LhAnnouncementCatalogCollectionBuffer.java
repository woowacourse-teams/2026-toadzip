package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;

/** 모든 페이지가 첫 페이지와 같은 검색 기간으로 응답했는지 확인하며 공고 목록을 모은다. */
public final class LhAnnouncementCatalogCollectionBuffer
        implements PageBuffer<LhAnnouncementCatalogPage, LhAnnouncementCatalogCollectedResponse> {

    private final PagedCollectionBuffer<LhAnnouncementCatalogRow> pages = new PagedCollectionBuffer<>("LH 공고 목록");
    private LhAnnouncementCatalogPage first;

    @Override
    public void add(LhAnnouncementCatalogPage page) {
        if (first == null) {
            first = page;
        }
        page.validateWindow(first);
        pages.add(page.page(), row -> row.snapshot().validateIdentifiers());
    }

    @Override
    public int totalCount() {
        return pages.totalCount();
    }

    @Override
    public boolean isComplete() {
        return pages.isComplete();
    }

    @Override
    public LhAnnouncementCatalogCollectedResponse finish(Instant collectedAt) {
        SourcePage<LhAnnouncementCatalogRow> collected = pages.finish();
        return new LhAnnouncementCatalogCollectedResponse(collected.totalCount(), first.startDate(), first.endDate(),
                collectedAt, collected.rows());
    }
}
