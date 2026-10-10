package com.toadzip.backend.ingest.failure.service;

import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse;
import com.toadzip.backend.ingest.failure.repository.IngestFailureReviewQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestFailureReviewQueryService {

    private final IngestFailureReviewQueryRepository repository;

    public IngestFailureReviewQueryService(IngestFailureReviewQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public IngestFailureReviewPageResponse findReviews(
            String domain, String category, String status, int page, int size
    ) {
        return repository.findReviews(domain, category, status, page, size);
    }
}
