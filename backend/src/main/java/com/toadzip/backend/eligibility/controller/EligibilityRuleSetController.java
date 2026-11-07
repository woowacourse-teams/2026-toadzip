package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftVersion;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.eligibility.dto.EligibilityResponses.RuleSet;
import com.toadzip.backend.eligibility.repository.EligibilityJsonCodec;
import com.toadzip.backend.eligibility.service.EligibilityRuleSetService;
import com.toadzip.backend.global.response.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/announcements/{announcementId}/basic-comparison-rule-set/draft")
public class EligibilityRuleSetController {
    private final EligibilityRuleSetService ruleSets;

    private final EligibilityJsonCodec codec;

    public EligibilityRuleSetController(
            EligibilityRuleSetService ruleSets, EligibilityJsonCodec codec) {
        this.ruleSets = ruleSets;
        this.codec = codec;
    }

    @GetMapping
    public ApiResponse<RuleSet> draft(@PathVariable long announcementId) {
        return new ApiResponse<>(ruleSets.draft(announcementId));
    }

    @PutMapping
    public ApiResponse<RuleSet> save(
            @PathVariable long announcementId, @RequestBody JsonNode request) {
        return new ApiResponse<>(
                ruleSets.save(announcementId, codec.request(request, SaveDraft.class)));
    }

    @PostMapping("/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RuleSet> copy(@PathVariable long announcementId) {
        return new ApiResponse<>(ruleSets.copy(announcementId));
    }

    @PostMapping("/validation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validate(@PathVariable long announcementId, @RequestBody JsonNode request) {
        ruleSets.validate(announcementId, codec.request(request, DraftVersion.class));
    }

    @PostMapping("/publish")
    public ApiResponse<RuleSet> publish(
            @PathVariable long announcementId, @RequestBody JsonNode request) {
        return new ApiResponse<>(
                ruleSets.publish(announcementId, codec.request(request, DraftVersion.class)));
    }
}
