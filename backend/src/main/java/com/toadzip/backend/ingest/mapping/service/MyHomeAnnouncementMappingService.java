package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지원을 종료한 마이홈 공고 정제가 남긴 실패 목록을 조회한다. */
@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementMappingService {

    private final MyHomeAnnouncementMappingFailureStore failureStore;

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementMappingFailureResponse> findFailures(int page, int size) {
        return failureStore.pending(page, size)
                .stream()
                .map(MyHomeAnnouncementMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementMappingFailureResponse> findFailureHistory(int page, int size) {
        return failureStore.history(page, size)
                .stream()
                .map(MyHomeAnnouncementMappingFailureResponse::from)
                .toList();
    }
}
