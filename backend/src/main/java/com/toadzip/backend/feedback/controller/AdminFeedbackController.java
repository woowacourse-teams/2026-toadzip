package com.toadzip.backend.feedback.controller;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.feedback.dto.FeedbackSummary;
import com.toadzip.backend.feedback.service.AdminFeedbackQueryService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/feedback")
public class AdminFeedbackController {

    private final AdminFeedbackQueryService service;

    public AdminFeedbackController(AdminFeedbackQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AdminPage<FeedbackSummary>> search(
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return new ApiResponse<>(service.search(keyword.strip(), page, size));
    }
}
