package com.toadzip.backend.ingest.service;

import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;

@Service
public class LhNoticeDetailCollectionService {

    private final LhNoticeExternalCollectionService collectionService;

    public LhNoticeDetailCollectionService(LhNoticeExternalCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    public ExternalDataCollectionReport collect() {
        return collectionService.collect(ExternalDataSource.LH_NOTICE_DETAIL);
    }
}
