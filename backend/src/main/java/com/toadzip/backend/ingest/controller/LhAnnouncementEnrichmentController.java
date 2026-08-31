package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.LhAnnouncementEnrichmentFailureResponse;
import com.toadzip.backend.ingest.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.service.LhAnnouncementEnrichmentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
