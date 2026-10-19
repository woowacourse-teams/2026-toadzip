package com.toadzip.backend.housing.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.dto.request.ComplexComparisonLinkRequest;
import com.toadzip.backend.housing.dto.request.ComplexComparisonUpdateRequest;
import com.toadzip.backend.housing.dto.response.ComplexComparisonResponse;
import com.toadzip.backend.housing.service.ComplexComparisonService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/housing-complexes/{id}/verification/comparison")
public class AdminComplexComparisonController {
    private final ComplexComparisonService comparison;
    private final IngestExecutionOwnershipService ownership;

    public AdminComplexComparisonController(ComplexComparisonService comparison,
            IngestExecutionOwnershipService ownership) {
        this.comparison = comparison;
        this.ownership = ownership;
    }

    @GetMapping
    public ApiResponse<ComplexComparisonResponse> detail(@PathVariable long id) {
        return new ApiResponse<>(comparison.detail(id));
    }

    @PutMapping
    public ApiResponse<ComplexComparisonResponse> update(@PathVariable long id,
            @Valid @RequestBody ComplexComparisonUpdateRequest request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.update(id, request, principal.getName()));
        }
    }

    @PostMapping("/sources")
    public ApiResponse<ComplexComparisonResponse> link(@PathVariable long id,
            @Valid @RequestBody ComplexComparisonLinkRequest request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.link(id, request, principal.getName()));
        }
    }

    @DeleteMapping("/sources")
    public ApiResponse<ComplexComparisonResponse> unlink(@PathVariable long id,
            @Valid @RequestBody ComplexComparisonLinkRequest request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.unlink(id, request, principal.getName()));
        }
    }
}
