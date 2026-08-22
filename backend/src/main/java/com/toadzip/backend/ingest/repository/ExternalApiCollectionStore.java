package com.toadzip.backend.ingest.repository;

import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.ExternalApiCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalApiData;

@Repository
public class ExternalApiCollectionStore {

    private final ExternalApiDataRepository apiDataRepository;

    private final ExternalApiCollectionFailureRepository failureRepository;

    public ExternalApiCollectionStore(
            ExternalApiDataRepository apiDataRepository,
            ExternalApiCollectionFailureRepository failureRepository
    ) {
        this.apiDataRepository = apiDataRepository;
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void storeApiData(List<ExternalApiData> apiData) {
        apiDataRepository.saveAll(apiData);
    }

    @Transactional
    public void storeFailure(ExternalApiCollectionFailure failure) {
        failureRepository.save(failure);
    }
}
