package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityDetail;
import com.toadzip.backend.eligibility.dto.EligibilityProcessingResponses.Discoveries;
import com.toadzip.backend.eligibility.dto.EligibilityProcessingResponses.Progress;
import com.toadzip.backend.eligibility.service.EligibilityQueryService;
import com.toadzip.backend.global.response.ApiResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EligibilityController {
    private final EligibilityQueryService queries;

    public EligibilityController(EligibilityQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v2/eligibility/processing")
    public ResponseEntity<ApiResponse<Progress>> progress(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(
                queries.progress(Long.parseLong(authentication.getName()))));
    }

    @GetMapping("/api/v2/eligibility/discoveries")
    public ResponseEntity<ApiResponse<Discoveries>> discoveries(Authentication authentication,
            @RequestParam(defaultValue = "0") long afterAnnouncementId, @RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(
                queries.discoveries(Long.parseLong(authentication.getName()), afterAnnouncementId, limit)));
    }

    @PostMapping("/api/v2/eligibility/discoveries/{rootAnnouncementId}/ack")
    public ResponseEntity<Void> acknowledge(Authentication authentication, @PathVariable long rootAnnouncementId,
            @RequestParam UUID eventId) {
        queries.acknowledge(Long.parseLong(authentication.getName()), rootAnnouncementId, eventId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v2/announcements/{announcementId}/eligibility")
    public ResponseEntity<ApiResponse<EligibilityDetail>> detail(Authentication authentication,
            @PathVariable long announcementId, @RequestParam(required = false) String region) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(
                queries.detail(Long.parseLong(authentication.getName()), announcementId, region)));
    }
}
