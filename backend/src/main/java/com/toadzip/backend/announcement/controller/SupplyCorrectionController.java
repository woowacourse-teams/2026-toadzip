package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.service.SupplyCorrectionService;
import com.toadzip.backend.announcement.service.SupplyReviewService;
import com.toadzip.backend.announcement.dto.correction.SupplyReview;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/announcements/{id}/supply-corrections")
public class SupplyCorrectionController {
    private final SupplyCorrectionService service;
    private final IngestExecutionOwnershipService ownership;
    private final SupplyReviewService reviews;

    @GetMapping
    public ApiResponse<SupplyCorrection.Workspace> workspace(@PathVariable long id) {
        return new ApiResponse<>(service.workspace(id));
    }

    @GetMapping("/reviews")
    public ApiResponse<SupplyReview.Overview> reviews(@PathVariable long id) {
        return new ApiResponse<>(reviews.overview(id));
    }

    @PostMapping("/reviews")
    public ApiResponse<SupplyReview.Overview> review(@PathVariable long id,
            @Valid @RequestBody SupplyReview.Request request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(reviews.record(id, request, principal.getName()));
        }
    }

    @PostMapping("/preview")
    public ApiResponse<SupplyCorrection.Preview> preview(@PathVariable long id,
            @Valid @RequestBody SupplyCorrection.Request request) {
        return new ApiResponse<>(service.preview(id, request));
    }

    @PostMapping
    public ApiResponse<SupplyCorrection.Applied> apply(@PathVariable long id,
            @Valid @RequestBody SupplyCorrection.Apply request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.apply(id, request, principal.getName()));
        }
    }
}
