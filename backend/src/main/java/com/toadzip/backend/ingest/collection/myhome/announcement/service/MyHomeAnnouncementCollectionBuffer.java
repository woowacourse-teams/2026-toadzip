package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;

public final class MyHomeAnnouncementCollectionBuffer {

    private final MyHomeAnnouncementCollectionRequest request;
    private final PagedCollectionBuffer<MyHomeAnnouncementSourceSnapshot> pages =
            new PagedCollectionBuffer<>("마이홈 공고");

    public MyHomeAnnouncementCollectionBuffer(MyHomeAnnouncementCollectionRequest request) {
        this.request = request;
    }

    public void add(SourcePage<MyHomeAnnouncementSourceSnapshot> page) {
        pages.add(page, MyHomeAnnouncementSourceSnapshot::validateIdentifiers);
    }

    public boolean isComplete() {
        return pages.isComplete();
    }

    public MyHomeAnnouncementCollectedResponse finish(Instant collectedAt) {
        SourcePage<MyHomeAnnouncementSourceSnapshot> page = pages.finish();
        MyHomeAnnouncementCollectedResponse response = new MyHomeAnnouncementCollectedResponse(
                page.totalCount(), collectedAt, page.rows());
        response.validateFor(request);
        return response;
    }
}
