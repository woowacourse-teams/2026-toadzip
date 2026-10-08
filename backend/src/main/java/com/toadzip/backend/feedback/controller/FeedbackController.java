package com.toadzip.backend.feedback.controller;

import com.toadzip.backend.feedback.dto.FeedbackSubmissionRequest;
import com.toadzip.backend.feedback.dto.FeedbackSubmissionResponse;
import com.toadzip.backend.feedback.service.FeedbackSubmissionService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackSubmissionService service;

    public FeedbackController(FeedbackSubmissionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FeedbackSubmissionResponse> submit(@Valid @RequestBody FeedbackSubmissionRequest request) {
        return new ApiResponse<>(new FeedbackSubmissionResponse(service.submit(request.content())));
    }
}
