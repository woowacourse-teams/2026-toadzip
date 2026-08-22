package com.toadzip.backend.ingest.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.service.MyHomeComplexCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/myhome/complexes")
public class MyHomeComplexCollectionController {

    private final MyHomeComplexCollectionService collectionService;

    public MyHomeComplexCollectionController(MyHomeComplexCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @PostMapping
    public ExternalDataCollectionReport collect(
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String districtCode,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(1_000) int pageSize,
            @RequestParam(defaultValue = "50") @Min(1) @Max(1_000) int maxPages
    ) {
        return collectionService.collect(new MyHomeComplexCollectionRequest(
                provinceCode,
                districtCode,
                pageSize,
                maxPages
        ));
    }
}
