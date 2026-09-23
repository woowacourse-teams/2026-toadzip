package com.toadzip.backend.ingest.enrichment.controller;

import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/announcement-enrichments")
public class LhAnnouncementEnrichmentController {

    private final LhAnnouncementEnrichmentService enrichmentService;

    public LhAnnouncementEnrichmentController(LhAnnouncementEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    @PostMapping
    public ResponseEntity<LhAnnouncementEnrichmentReport> enrichAll() {
        return ResponseEntity.ok(enrichmentService.enrichAll());
    }

    @GetMapping("/failures")
    public ResponseEntity<List<LhAnnouncementEnrichmentFailureResponse>> findFailures() {
        return ResponseEntity.ok(enrichmentService.findFailures());
    }

    @GetMapping("/failures/page")
    public ResponseEntity<List<LhAnnouncementEnrichmentFailureResponse>> findFailurePage(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(enrichmentService.findFailures(page, size));
    }

    @GetMapping("/failures/history")
    public ResponseEntity<List<LhAnnouncementEnrichmentFailureResponse>> findFailureHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(enrichmentService.findFailureHistory(page, size));
    }
}
