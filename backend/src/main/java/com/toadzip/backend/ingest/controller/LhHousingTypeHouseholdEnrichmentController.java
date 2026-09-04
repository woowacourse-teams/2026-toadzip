package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.service.LhHousingTypeHouseholdEnrichmentService;
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
}
