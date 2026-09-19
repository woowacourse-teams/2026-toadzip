package com.toadzip.backend.ingest.enrichment.controller;

import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/housing-type-households")
public class LhHousingTypeHouseholdEnrichmentController {

    private final LhHousingTypeHouseholdEnrichmentService enrichmentService;

    public LhHousingTypeHouseholdEnrichmentController(
            LhHousingTypeHouseholdEnrichmentService enrichmentService
    ) {
        this.enrichmentService = enrichmentService;
    }

    @PostMapping
    public ResponseEntity<LhHousingTypeHouseholdEnrichmentReport> enrichAll() {
        return ResponseEntity.ok(enrichmentService.enrichAll());
    }

    @GetMapping("/failures")
    public ResponseEntity<List<LhHouseholdEnrichmentFailureResponse>> findFailures() {
        return ResponseEntity.ok(enrichmentService.findFailures());
    }

    @GetMapping("/failures/history")
    public ResponseEntity<List<LhHouseholdEnrichmentFailureResponse>> findFailureHistory() {
        return ResponseEntity.ok(enrichmentService.findFailureHistory());
    }
}
