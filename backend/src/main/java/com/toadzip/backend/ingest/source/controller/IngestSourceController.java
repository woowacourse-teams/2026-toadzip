package com.toadzip.backend.ingest.source.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse;
import com.toadzip.backend.ingest.source.service.IngestSourceQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/sources")
public class IngestSourceController {

    private final IngestSourceQueryService service;

    public IngestSourceController(IngestSourceQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<IngestSourcePageResponse> findSources(
            @RequestParam IngestSourceCategory category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword
    ) {
        return new ApiResponse<>(service.findSources(category, page, size, keyword.strip()));
    }
}
