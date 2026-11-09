package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexRowValidator;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;

public final class MyHomeComplexCollectionBuffer
        implements PageBuffer<SourcePage<MyHomeComplexSourceSnapshot>, MyHomeComplexCollectedResponse> {

    private final MyHomeComplexCollectionRequest request;
    private final MyHomeComplexRowValidator validator;
    private final PagedCollectionBuffer<MyHomeComplexSourceSnapshot> pages =
            new PagedCollectionBuffer<>("마이홈 단지");

    public MyHomeComplexCollectionBuffer(MyHomeComplexCollectionRequest request) {
        this.request = request;
        validator = new MyHomeComplexRowValidator(request.provinceCode(), request.districtCode());
    }

    @Override
    public void add(SourcePage<MyHomeComplexSourceSnapshot> page) {
        pages.add(page, validator::validate);
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
    public MyHomeComplexCollectedResponse finish(Instant collectedAt) {
        SourcePage<MyHomeComplexSourceSnapshot> page = pages.finish();
        var response = new MyHomeComplexCollectedResponse(page.totalCount(), collectedAt, page.rows());
        response.validateFor(request);
        return response;
    }
}
