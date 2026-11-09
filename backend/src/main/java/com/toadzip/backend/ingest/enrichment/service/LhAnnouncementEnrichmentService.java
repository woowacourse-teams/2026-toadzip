package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지원을 종료한 LH 공고 보강이 남긴 실패 목록을 조회한다. */
@Service
@RequiredArgsConstructor
public class LhAnnouncementEnrichmentService {

    private final LhAnnouncementEnrichmentFailureStore failureStore;

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailures(int page, int size) {
        return failureStore.pending(page, size).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementEnrichmentFailureResponse> findFailureHistory(int page, int size) {
        return failureStore.history(page, size).stream()
                .map(LhAnnouncementEnrichmentFailureResponse::from)
                .toList();
    }
}
