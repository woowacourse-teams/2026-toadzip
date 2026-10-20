package com.toadzip.backend.ingest.failure.service;

import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse;
import com.toadzip.backend.ingest.failure.repository.IngestFailureReviewQueryRepository;
import com.toadzip.backend.ingest.failure.exception.IngestFailureReviewNotFoundException;
import java.util.UUID;
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
            String domain, String category, String status, int page, int size, String keyword, UUID executionId
    ) {
        return repository.findReviews(domain, category, status, page, size, keyword, executionId);
    }

    @Transactional(readOnly = true)
    public IngestFailureReviewPageResponse.Item findReview(String category, long id) {
        return repository.findReview(category, id).orElseThrow(IngestFailureReviewNotFoundException::new);
    }
}
