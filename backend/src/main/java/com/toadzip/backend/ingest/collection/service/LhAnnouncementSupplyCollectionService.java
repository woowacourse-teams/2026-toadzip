package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import org.springframework.stereotype.Service;

@Service
public class LhAnnouncementSupplyCollectionService {

    private final LhAnnouncementExternalCollectionService collectionService;

    public LhAnnouncementSupplyCollectionService(LhAnnouncementExternalCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    public ExternalDataCollectionReport collect() {
        return collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
    }

    public ExternalDataCollectionReport refresh(String pblancId) {
        return collectionService.refresh(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, pblancId);
    }
}
