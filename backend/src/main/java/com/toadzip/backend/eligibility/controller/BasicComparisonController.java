package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityResponses.BasicComparisonDetail;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.BasicComparisonSummary;
import com.toadzip.backend.eligibility.service.BasicComparisonQueryService;
import com.toadzip.backend.global.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Preserves the basic-condition comparison contract separately from eligibility decisions. */
@RestController
@RequestMapping("/api/v2")
public class BasicComparisonController {
    private final BasicComparisonQueryService queries;

    public BasicComparisonController(BasicComparisonQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/announcements/{announcementId}/basic-comparison")
    public ResponseEntity<ApiResponse<BasicComparisonDetail>> detail(
            Authentication authentication, @PathVariable long announcementId) {
        return privateResponse(queries.detail(userId(authentication), announcementId));
    }

    @GetMapping("/eligibility/summary")
    public ResponseEntity<ApiResponse<BasicComparisonSummary>> summary(Authentication authentication) {
        return privateResponse(queries.summary(userId(authentication)));
    }

    private long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private <T> ResponseEntity<ApiResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(data));
    }
}
