package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;

public final class MyHomeAnnouncementCollectionBuffer
        implements PageBuffer<SourcePage<MyHomeAnnouncementSourceSnapshot>, MyHomeAnnouncementCollectedResponse> {

    private final MyHomeAnnouncementCollectionRequest request;
    private final PagedCollectionBuffer<MyHomeAnnouncementSourceSnapshot> pages =
            new PagedCollectionBuffer<>("마이홈 공고");

    public MyHomeAnnouncementCollectionBuffer(MyHomeAnnouncementCollectionRequest request) {
        this.request = request;
    }

    @Override
    public void add(SourcePage<MyHomeAnnouncementSourceSnapshot> page) {
        pages.add(page, MyHomeAnnouncementSourceSnapshot::validateIdentifiers);
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
    public MyHomeAnnouncementCollectedResponse finish(Instant collectedAt) {
        SourcePage<MyHomeAnnouncementSourceSnapshot> page = pages.finish();
        MyHomeAnnouncementCollectedResponse response = new MyHomeAnnouncementCollectedResponse(
                page.totalCount(), collectedAt, page.rows());
        response.validateFor(request);
        return response;
    }
}
