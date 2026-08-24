package com.toadzip.backend.ingest.repository;

import java.time.Instant;
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
    public int storeApiData(List<ExternalApiData> apiData) {
        apiDataRepository.saveAll(apiData);
        return apiData.size();
    }

    @Transactional
    public void completeLhNoticeProcessing(ExternalApiData sourceApiData, Instant processedAt) {
        sourceApiData.completeLhNoticeProcessing(processedAt);
        apiDataRepository.save(sourceApiData);
    }

    @Transactional
    public void failLhNoticeProcessing(ExternalApiData sourceApiData, Instant processedAt) {
        sourceApiData.failLhNoticeProcessing(processedAt);
        apiDataRepository.save(sourceApiData);
    }

    @Transactional
    public void storeFailure(ExternalApiCollectionFailure failure) {
        failureRepository.save(failure);
    }
}
