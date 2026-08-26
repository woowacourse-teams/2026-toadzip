package com.toadzip.backend.ingest.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.service.LhLeaseCatalogCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/lh/lease-catalog")
public class LhLeaseCatalogCollectionController {

    private final LhLeaseCatalogCollectionService collectionService;

    public LhLeaseCatalogCollectionController(LhLeaseCatalogCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public ExternalDataCollectionReport collect(
            @RequestParam(defaultValue = "9999")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 10_000, message = "10000 이하여야 합니다.") int pageSize,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 10_000, message = "10000 이하여야 합니다.") int maxPages
    ) {
        return collectionService.collect(new LhLeaseCatalogCollectionRequest(pageSize, maxPages));
    }
}
