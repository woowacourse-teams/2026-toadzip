package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;

public final class LhLeaseCatalogCollectionBuffer
        implements PageBuffer<SourcePage<LhCatalogSourceSnapshot>, LhLeaseCatalogCollectedResponse> {

    private final PagedCollectionBuffer<LhCatalogSourceSnapshot> pages =
            new PagedCollectionBuffer<>("LH 임대 카탈로그");

    @Override
    public void add(SourcePage<LhCatalogSourceSnapshot> page) {
        pages.add(page, LhCatalogSourceSnapshot::validateIdentifiers);
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
    public LhLeaseCatalogCollectedResponse finish(Instant collectedAt) {
        SourcePage<LhCatalogSourceSnapshot> page = pages.finish();
        return new LhLeaseCatalogCollectedResponse(page.totalCount(), collectedAt, page.rows());
    }
}
