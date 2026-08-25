package com.toadzip.backend.ingest.service;

import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;

@Service
public class LhNoticeDetailCollectionService {

    private final LhNoticeApiCollectionService collectionService;

    public LhNoticeDetailCollectionService(LhNoticeApiCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    public ExternalApiCollectionReport collect() {
        return collectionService.collect(ExternalApi.LH_NOTICE_DETAIL);
    }
}
