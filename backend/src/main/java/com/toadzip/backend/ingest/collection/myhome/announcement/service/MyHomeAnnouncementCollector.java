package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.paging.service.PagedSource;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementCollector implements PagedSource<
        MyHomeAnnouncementCollectionRequest, SourcePage<MyHomeAnnouncementSourceSnapshot>,
        MyHomeAnnouncementCollectedResponse> {

    private final MyHomeAnnouncementStorageService storage;
    private final MyHomeAnnouncementApiRepository apiRepository;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.MYHOME_ANNOUNCEMENT;
    }

    @Override
    public SourcePage<MyHomeAnnouncementSourceSnapshot> fetch(MyHomeAnnouncementCollectionRequest request, int page) {
        return apiRepository.fetch(request, page);
    }

    @Override
    public MyHomeAnnouncementCollectionBuffer newBuffer(MyHomeAnnouncementCollectionRequest request) {
        return new MyHomeAnnouncementCollectionBuffer(request);
    }

    @Override
    public void store(
            UUID recordId, MyHomeAnnouncementCollectionRequest request, MyHomeAnnouncementCollectedResponse collected
    ) {
        storage.complete(recordId, request, collected);
    }

    @Override
    public String failureLogMessage() {
        return "마이홈 공고 공급유형 수집 실패";
    }
}
