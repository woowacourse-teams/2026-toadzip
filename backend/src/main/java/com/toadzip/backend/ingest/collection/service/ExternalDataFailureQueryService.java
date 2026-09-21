package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionFailureResponse;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalDataFailureQueryService {

    private final ExternalDataCollectionFailureRepository repository;

    public ExternalDataFailureQueryService(ExternalDataCollectionFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ExternalDataCollectionFailureResponse> findPending(int page, int size) {
        return repository.findLatestPendingByRequest(PageRequest.of(page, size))
                .stream()
                .map(ExternalDataCollectionFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExternalDataCollectionFailureResponse> findHistory(int page, int size) {
        return repository.findAllByOrderByLastOccurredAtDescIdDesc(PageRequest.of(page, size))
                .stream()
                .map(ExternalDataCollectionFailureResponse::from)
                .toList();
    }
}
