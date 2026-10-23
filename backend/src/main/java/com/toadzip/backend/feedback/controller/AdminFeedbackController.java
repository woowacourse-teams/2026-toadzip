package com.toadzip.backend.feedback.controller;

import com.toadzip.backend.feedback.service.AdminFeedbackProcessingService;
import com.toadzip.backend.feedback.domain.FeedbackStatus;
import com.toadzip.backend.feedback.dto.FeedbackProcessingRequest;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
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
    private final AdminFeedbackProcessingService processing;

    public AdminFeedbackController(AdminFeedbackQueryService service,
            AdminFeedbackProcessingService processing) {
        this.service = service;
        this.processing = processing;
    }

    @GetMapping
    public ApiResponse<AdminPage<FeedbackSummary>> search(
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return new ApiResponse<>(service.search(keyword.strip(), status, page, size));
    }
    @GetMapping("/{id}")
    public ApiResponse<FeedbackSummary> find(@PathVariable long id) {
        return new ApiResponse<>(processing.find(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<FeedbackSummary> process(@PathVariable long id,
            @RequestBody @Valid
            FeedbackProcessingRequest request, Principal actor) {
        return new ApiResponse<>(processing.process(id, request, actor.getName()));
    }

    @GetMapping("/{id}/changes")
    public ApiResponse<List<AdminChangeResponse>> history(
            @PathVariable long id) {
        return new ApiResponse<>(processing.history(id));
    }
}
