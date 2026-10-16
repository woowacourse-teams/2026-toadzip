package com.toadzip.backend.admin.quality.controller;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.SaveReviewDraft;
import com.toadzip.backend.admin.quality.dto.SharedReviewDraft;
import com.toadzip.backend.admin.quality.service.SharedReviewDraftService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quality/{resource}/{id}/draft")
public class SharedReviewDraftController {
    private final SharedReviewDraftService drafts;

    public SharedReviewDraftController(SharedReviewDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public ApiResponse<SharedReviewDraft> find(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestParam @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target) {
        return new ApiResponse<>(drafts.find(resource, id, target));
    }

    @PutMapping
    public ApiResponse<SharedReviewDraft> save(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestBody @Valid SaveReviewDraft request, Principal actor) {
        return new ApiResponse<>(drafts.save(resource, id, request, actor.getName()));
    }
}
