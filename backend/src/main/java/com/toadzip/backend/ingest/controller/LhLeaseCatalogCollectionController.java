package com.toadzip.backend.ingest.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
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
    public ExternalApiCollectionReport collect(
            @RequestParam(defaultValue = "9999") @Min(1) @Max(10_000) int pageSize,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int maxPages
    ) {
        return collectionService.collect(new LhLeaseCatalogCollectionRequest(pageSize, maxPages));
    }
}
