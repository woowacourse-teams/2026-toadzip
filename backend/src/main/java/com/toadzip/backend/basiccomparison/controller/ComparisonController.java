package com.toadzip.backend.basiccomparison.controller;

import com.toadzip.backend.basiccomparison.dto.ComparisonRequests.SaveProfile;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Catalog;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Detail;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Profile;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Summary;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.service.ComparisonProfileService;
import com.toadzip.backend.basiccomparison.service.ComparisonQueryService;
import com.toadzip.backend.global.response.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v2")
public class ComparisonController {
    private final ComparisonProfileService profiles;
    private final ComparisonQueryService queries;
    private final ComparisonJsonCodec codec;

    public ComparisonController(
            ComparisonProfileService profiles,
            ComparisonQueryService queries,
            ComparisonJsonCodec codec) {
        this.profiles = profiles;
        this.queries = queries;
        this.codec = codec;
    }

    @GetMapping("/me/eligibility-profile")
    public ResponseEntity<ApiResponse<Profile>> profile(Authentication authentication) {
        return privateResponse(profiles.find(userId(authentication)));
    }

    @PutMapping("/me/eligibility-profile")
    public ResponseEntity<ApiResponse<Profile>> saveProfile(
            Authentication authentication, @RequestBody JsonNode request) {
        return privateResponse(
                profiles.save(userId(authentication), codec.request(request, SaveProfile.class)));
    }

    @GetMapping("/eligibility/input-catalog")
    public ResponseEntity<ApiResponse<Catalog>> catalog() {
        return privateResponse(profiles.catalog());
    }

    @GetMapping("/announcements/{announcementId}/basic-comparison")
    public ResponseEntity<ApiResponse<Detail>> detail(
            Authentication authentication, @PathVariable long announcementId) {
        return privateResponse(queries.detail(userId(authentication), announcementId));
    }

    @GetMapping("/eligibility/summary")
    public ResponseEntity<ApiResponse<Summary>> summary(Authentication authentication) {
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
