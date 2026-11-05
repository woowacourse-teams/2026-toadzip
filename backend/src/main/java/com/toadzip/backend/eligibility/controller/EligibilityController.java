package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Catalog;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Detail;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Profile;
import com.toadzip.backend.eligibility.service.EligibilityProfileService;
import com.toadzip.backend.eligibility.service.EligibilityQueryService;
import com.toadzip.backend.global.response.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EligibilityController {
    private final EligibilityProfileService profiles;
    private final EligibilityQueryService queries;

    public EligibilityController(
            EligibilityProfileService profiles, EligibilityQueryService queries) {
        this.profiles = profiles;
        this.queries = queries;
    }

    @GetMapping("/me/eligibility-profile")
    public ResponseEntity<ApiResponse<Profile>> profile(Authentication authentication) {
        return privateResponse(profiles.find(userId(authentication)));
    }

    @PutMapping("/me/eligibility-profile")
    public ResponseEntity<ApiResponse<Profile>> saveProfile(
            Authentication authentication, @RequestBody @Valid SaveProfile request) {
        return privateResponse(profiles.save(userId(authentication), request));
    }

    @GetMapping("/eligibility/input-catalog")
    public ResponseEntity<ApiResponse<Catalog>> catalog() {
        return privateResponse(profiles.catalog());
    }

    @GetMapping("/announcements/{announcementId}/eligibility")
    public ResponseEntity<ApiResponse<Detail>> detail(
            Authentication authentication, @PathVariable long announcementId) {
        return privateResponse(queries.detail(userId(authentication), announcementId));
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
