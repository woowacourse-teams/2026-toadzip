package com.toadzip.backend.ingest.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.service.MyHomeNoticeCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/myhome/notices")
public class MyHomeNoticeCollectionController {

    private final MyHomeNoticeCollectionService collectionService;

    public MyHomeNoticeCollectionController(MyHomeNoticeCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public ExternalDataCollectionReport collect(
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int pageSize,
            @RequestParam(defaultValue = "50") @Min(1) @Max(1_000) int maxPages
    ) {
        return collectionService.collect(new MyHomeNoticeCollectionRequest(pageSize, maxPages));
    }
}
