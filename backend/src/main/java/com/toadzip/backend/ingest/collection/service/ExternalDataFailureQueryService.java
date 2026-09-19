package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionFailureResponse;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalDataFailureQueryService {

    private final ExternalDataCollectionFailureRepository repository;

    public ExternalDataFailureQueryService(ExternalDataCollectionFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ExternalDataCollectionFailureResponse> findPending() {
        return repository.findLatestPendingByRequest()
                .stream()
                .map(ExternalDataCollectionFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExternalDataCollectionFailureResponse> findHistory() {
        return repository.findAllByOrderByLastOccurredAtDesc()
                .stream()
                .map(ExternalDataCollectionFailureResponse::from)
                .toList();
    }
}
