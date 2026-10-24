package com.toadzip.backend.ingest.enrichment.controller;

import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.ReleaseRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.SaveRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.View;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhHouseholdComplexLinkService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/housing-type-households")
public class LhHousingTypeHouseholdEnrichmentController {

    private final LhHousingTypeHouseholdEnrichmentService enrichmentService;
    private final LhHouseholdComplexLinkService linkService;

    public LhHousingTypeHouseholdEnrichmentController(
            LhHousingTypeHouseholdEnrichmentService enrichmentService,
            LhHouseholdComplexLinkService linkService
    ) {
        this.enrichmentService = enrichmentService;
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<LhHousingTypeHouseholdEnrichmentReport> enrichAll() {
        return ResponseEntity.ok(enrichmentService.enrichAll());
    }

    @GetMapping("/failures")
    public ResponseEntity<List<LhHouseholdEnrichmentFailureResponse>> findFailures(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(enrichmentService.findFailures(page, size));
    }

    @GetMapping("/failures/history")
    public ResponseEntity<List<LhHouseholdEnrichmentFailureResponse>> findFailureHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(enrichmentService.findFailureHistory(page, size));
    }

    @GetMapping("/failures/{failureId}/complex-link")
    public View complexLink(@PathVariable long failureId) {
        return linkService.view(failureId);
    }

    @PostMapping("/failures/{failureId}/complex-link")
    public View saveComplexLink(@PathVariable long failureId, @Valid @RequestBody SaveRequest request,
            Principal principal) {
        return linkService.save(failureId, request, principal.getName());
    }

    @PostMapping("/failures/{failureId}/complex-link/release")
    public View releaseComplexLink(@PathVariable long failureId, @Valid @RequestBody ReleaseRequest request,
            Principal principal) {
        return linkService.release(failureId, request, principal.getName());
    }
}
