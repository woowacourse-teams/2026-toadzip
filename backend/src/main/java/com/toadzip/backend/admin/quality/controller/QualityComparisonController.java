package com.toadzip.backend.admin.quality.controller;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.QualityComparison;
import com.toadzip.backend.admin.quality.dto.QualityComparisonRequest;
import com.toadzip.backend.admin.quality.service.QualityComparisonService;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quality/{resource}/{id}/comparison")
public class QualityComparisonController {
    private final QualityComparisonService comparison;
    private final IngestExecutionOwnershipService ownership;

    public QualityComparisonController(QualityComparisonService comparison, IngestExecutionOwnershipService ownership) {
        this.comparison = comparison;
        this.ownership = ownership;
    }

    @GetMapping
    public ApiResponse<QualityComparison> detail(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestParam(defaultValue = "basic")
            @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target) {
        return new ApiResponse<>(comparison.detail(resource, id, target));
    }

    @PutMapping
    public ApiResponse<QualityComparison> update(@PathVariable QualityResource resource, @PathVariable long id,
            @Valid @RequestBody QualityComparisonRequest.Update request, Principal actor) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.update(resource, id, request, actor.getName()));
        }
    }

    @PostMapping("/sources")
    public ApiResponse<QualityComparison> link(@PathVariable QualityResource resource, @PathVariable long id,
            @Valid @RequestBody QualityComparisonRequest.Link request, Principal actor) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.link(resource, id, request, actor.getName(), false));
        }
    }

    @DeleteMapping("/sources")
    public ApiResponse<QualityComparison> unlink(@PathVariable QualityResource resource, @PathVariable long id,
            @Valid @RequestBody QualityComparisonRequest.Link request, Principal actor) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.link(resource, id, request, actor.getName(), true));
        }
    }

    @PostMapping("/checks")
    public ApiResponse<QualityComparison> check(@PathVariable QualityResource resource, @PathVariable long id,
            @Valid @RequestBody QualityComparisonRequest.Check request, Principal actor) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.check(resource, id, request, actor.getName()));
        }
    }

    @PostMapping("/checks/legacy")
    public ApiResponse<QualityComparison> checkComplex(@PathVariable QualityResource resource, @PathVariable long id,
            @Valid @RequestBody QualityComparisonRequest.Check request, Principal actor) {
        if (resource != QualityResource.COMPLEX) {
            throw new IllegalArgumentException("단지 기본 비교에서만 사용할 수 있습니다.");
        }
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(comparison.checkComplex(id, request, actor.getName()));
        }
    }
}
