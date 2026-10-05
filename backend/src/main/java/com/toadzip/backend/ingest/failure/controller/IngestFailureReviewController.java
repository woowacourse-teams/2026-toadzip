package com.toadzip.backend.ingest.failure.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse;
import com.toadzip.backend.ingest.failure.service.IngestFailureReviewQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/failure-reviews")
public class IngestFailureReviewController {

    private final IngestFailureReviewQueryService service;

    public IngestFailureReviewController(IngestFailureReviewQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<IngestFailureReviewPageResponse> findReviews(
            @RequestParam @Pattern(regexp = "complex|announcement") String domain,
            @RequestParam(defaultValue = "all")
            @Pattern(regexp = "all|collection|complex|household|announcement|enrichment") String category,
            @RequestParam(defaultValue = "PENDING") @Pattern(regexp = "PENDING|RESOLVED|ALL") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return new ApiResponse<>(service.findReviews(domain, category, status, page, size));
    }
}
