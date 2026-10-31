package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.paging.service.PagedSource;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyHomeComplexCollector implements PagedSource<
        MyHomeComplexCollectionRequest, SourcePage<MyHomeComplexSourceSnapshot>, MyHomeComplexCollectedResponse> {

    private final MyHomeComplexStorageService storage;
    private final MyHomeComplexApiRepository apiRepository;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.MYHOME_COMPLEX;
    }

    @Override
    public SourcePage<MyHomeComplexSourceSnapshot> fetch(MyHomeComplexCollectionRequest request, int page) {
        return apiRepository.fetch(request, page);
    }

    @Override
    public MyHomeComplexCollectionBuffer newBuffer(MyHomeComplexCollectionRequest request) {
        return new MyHomeComplexCollectionBuffer(request);
    }

    @Override
    public void store(UUID recordId, MyHomeComplexCollectionRequest request, MyHomeComplexCollectedResponse collected) {
        storage.complete(recordId, request, collected);
    }

    @Override
    public String failureLogMessage() {
        return "마이홈 단지 지역 수집에 실패했습니다";
    }

    /** 여러 지역을 함께 모으며 지역 단위로 진행을 알린다. */
    @Override
    public boolean reportsPageProgress() {
        return false;
    }
}
