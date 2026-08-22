package com.toadzip.backend.ingest.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.service.LhNoticeCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/lh/notices")
public class LhNoticeCollectionController {

    private final LhNoticeCollectionService collectionService;

    public LhNoticeCollectionController(LhNoticeCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public ExternalDataCollectionReport collect() {
        return collectionService.collect();
    }
}
