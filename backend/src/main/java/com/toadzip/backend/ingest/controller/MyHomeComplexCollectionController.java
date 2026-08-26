package com.toadzip.backend.ingest.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionReport;
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
    public MyHomeComplexCollectionReport collect(
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String districtCode,
            @RequestParam(defaultValue = "500")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 1_000, message = "1000 이하여야 합니다.") int pageSize,
            @RequestParam(defaultValue = "1000")
            @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 1_000, message = "1000 이하여야 합니다.") int maxPages
    ) {
        return collectionService.collect(new MyHomeComplexCollectionRequest(
                provinceCode,
                districtCode,
                pageSize,
                maxPages
        ));
    }
}
