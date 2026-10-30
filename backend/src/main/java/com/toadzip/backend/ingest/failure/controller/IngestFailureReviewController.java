package com.toadzip.backend.ingest.failure.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse;
import com.toadzip.backend.ingest.failure.service.IngestFailureReviewQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @Pattern(regexp = "all|collection|complex|announcement|enrichment") String category,
            @RequestParam(defaultValue = "PENDING") @Pattern(regexp = "PENDING|RESOLVED|ALL") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(required = false) UUID executionId
    ) {
        return new ApiResponse<>(service.findReviews(domain, category, status, page, size, keyword, executionId));
    }

    @GetMapping("/{category}/{id}")
    public ApiResponse<IngestFailureReviewPageResponse.Item> findReview(
            @PathVariable @Pattern(regexp = "collection|complex|announcement|enrichment") String category,
            @PathVariable @Min(1) long id
    ) {
        return new ApiResponse<>(service.findReview(category, id));
    }
}
