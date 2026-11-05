package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftVersion;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.service.RuleSetManagementService;
import com.toadzip.backend.global.response.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements/{announcementId}/eligibility-rule-set/draft")
public class RuleSetManagementController {
    private final RuleSetManagementService ruleSets;

    public RuleSetManagementController(RuleSetManagementService ruleSets) {
        this.ruleSets = ruleSets;
    }

    @GetMapping
    public ApiResponse<RuleSet> draft(@PathVariable long announcementId) {
        return new ApiResponse<>(ruleSets.draft(announcementId));
    }

    @PutMapping
    public ApiResponse<RuleSet> save(
            @PathVariable long announcementId, @RequestBody @Valid SaveDraft request) {
        return new ApiResponse<>(ruleSets.save(announcementId, request));
    }

    @PostMapping("/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RuleSet> copy(@PathVariable long announcementId) {
        return new ApiResponse<>(ruleSets.copy(announcementId));
    }

    @PostMapping("/validation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validate(
            @PathVariable long announcementId, @RequestBody @Valid DraftVersion request) {
        ruleSets.validate(announcementId, request);
    }

    @PostMapping("/publish")
    public ApiResponse<RuleSet> publish(
            @PathVariable long announcementId, @RequestBody @Valid DraftVersion request) {
        return new ApiResponse<>(ruleSets.publish(announcementId, request));
    }
}
