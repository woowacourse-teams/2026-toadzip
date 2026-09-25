package com.toadzip.backend.ingest.collection.controller;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/announcements")
public class LhAnnouncementCollectionController {

    private final LhAnnouncementCatalogCollectionService catalogCollectionService;

    private final LhAnnouncementDetailCollectionService detailCollectionService;

    private final LhAnnouncementSupplyCollectionService supplyCollectionService;

    public LhAnnouncementCollectionController(
            LhAnnouncementCatalogCollectionService catalogCollectionService,
            LhAnnouncementDetailCollectionService detailCollectionService,
            LhAnnouncementSupplyCollectionService supplyCollectionService
    ) {
        this.catalogCollectionService = catalogCollectionService;
        this.detailCollectionService = detailCollectionService;
        this.supplyCollectionService = supplyCollectionService;
    }

    @PostMapping("/catalog")
    public ResponseEntity<ExternalDataCollectionReport> collectCatalog() {
        return responseOf(catalogCollectionService.collect());
    }

    @PostMapping("/details")
    public ResponseEntity<ExternalDataCollectionReport> collectDetails() {
        return responseOf(detailCollectionService.collect());
    }

    @PostMapping("/supplies")
    public ResponseEntity<ExternalDataCollectionReport> collectSupplies() {
        return responseOf(supplyCollectionService.collect());
    }

    @PostMapping("/details/{pblancId}/refresh")
    public ResponseEntity<ExternalDataCollectionReport> refreshDetails(@PathVariable String pblancId) {
        return responseOf(detailCollectionService.refresh(pblancId));
    }

    @PostMapping("/supplies/{pblancId}/refresh")
    public ResponseEntity<ExternalDataCollectionReport> refreshSupplies(@PathVariable String pblancId) {
        return responseOf(supplyCollectionService.refresh(pblancId));
    }

    private ResponseEntity<ExternalDataCollectionReport> responseOf(ExternalDataCollectionReport report) {
        if (report.failedRequestCount() > 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(report);
        }
        return ResponseEntity.ok(report);
    }
}
