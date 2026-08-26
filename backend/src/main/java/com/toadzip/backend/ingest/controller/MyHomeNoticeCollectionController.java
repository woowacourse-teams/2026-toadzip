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
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 1_000, message = "1000 이하여야 합니다.") int pageSize,
            @RequestParam(defaultValue = "1000")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 1_000, message = "1000 이하여야 합니다.") int maxPages
    ) {
        return collectionService.collect(new MyHomeNoticeCollectionRequest(pageSize, maxPages));
    }
}
