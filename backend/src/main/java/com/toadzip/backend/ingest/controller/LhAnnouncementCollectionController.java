package com.toadzip.backend.ingest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.service.LhAnnouncementSupplyCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/lh/announcements")
public class LhAnnouncementCollectionController {

    private final LhAnnouncementDetailCollectionService detailCollectionService;

    private final LhAnnouncementSupplyCollectionService supplyCollectionService;

    public LhAnnouncementCollectionController(
            LhAnnouncementDetailCollectionService detailCollectionService,
            LhAnnouncementSupplyCollectionService supplyCollectionService
    ) {
        this.detailCollectionService = detailCollectionService;
        this.supplyCollectionService = supplyCollectionService;
    }

    @PostMapping("/details")
    public ResponseEntity<ExternalDataCollectionReport> collectDetails() {
        return responseOf(detailCollectionService.collect());
    }

    @PostMapping("/supplies")
    public ResponseEntity<ExternalDataCollectionReport> collectSupplies() {
        return responseOf(supplyCollectionService.collect());
    }

    private ResponseEntity<ExternalDataCollectionReport> responseOf(ExternalDataCollectionReport report) {
        if (report.failedRequestCount() > 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(report);
        }
        return ResponseEntity.ok(report);
    }
}
