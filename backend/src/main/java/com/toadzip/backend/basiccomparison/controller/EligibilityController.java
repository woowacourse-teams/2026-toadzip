package com.toadzip.backend.basiccomparison.controller;

import com.toadzip.backend.basiccomparison.dto.EligibilityDetail;
import com.toadzip.backend.basiccomparison.service.EligibilityQueryService;
import com.toadzip.backend.global.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EligibilityController {
    private final EligibilityQueryService queries;

    public EligibilityController(EligibilityQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v2/announcements/{announcementId}/eligibility")
    public ResponseEntity<ApiResponse<EligibilityDetail>> detail(Authentication authentication,
            @PathVariable long announcementId, @RequestParam(required = false) String region) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(
                queries.detail(Long.parseLong(authentication.getName()), announcementId, region)));
    }
}
