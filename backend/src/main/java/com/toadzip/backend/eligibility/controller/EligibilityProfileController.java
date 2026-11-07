package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Catalog;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.Profile;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.service.EligibilityProfileService;
import com.toadzip.backend.global.response.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v2")
public class EligibilityProfileController {
    private final EligibilityProfileService profiles;
    private final EligibilityJsonCodec codec;

    public EligibilityProfileController(
            EligibilityProfileService profiles,
            EligibilityJsonCodec codec) {
        this.profiles = profiles;
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

    private long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private <T> ResponseEntity<ApiResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(data));
    }
}
