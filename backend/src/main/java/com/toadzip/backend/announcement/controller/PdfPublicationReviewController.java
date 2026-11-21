package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.admin.controller.AdminManagementApi;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions.ReviewItem;
import com.toadzip.backend.global.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AdminManagementApi
@RequiredArgsConstructor
public class PdfPublicationReviewController {
    private final PdfPublicationDecisions decisions;

    public record ReviewPage(List<ReviewItem> items, Long nextBefore) {}

    @GetMapping("/api/admin/operations/pdf-publication-reviews")
    public ApiResponse<ReviewPage> reviews(@RequestParam(required = false) Long before) {
        var rows = decisions.reviewQueue(before == null ? Long.MAX_VALUE : before);
        return new ApiResponse<>(new ReviewPage(rows.stream().limit(20).toList(),
                rows.size() > 20 ? rows.get(19).jobId() : null));
    }
}
