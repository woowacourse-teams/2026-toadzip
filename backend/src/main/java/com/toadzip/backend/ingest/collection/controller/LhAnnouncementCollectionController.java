package com.toadzip.backend.ingest.collection.controller;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
import com.toadzip.backend.ingest.collection.service.VerifiedLhSupplyReplacementService;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/announcements")
public class LhAnnouncementCollectionController {

    private final LhAnnouncementCatalogCollectionService catalogCollectionService;

    private final LhAnnouncementDetailCollectionService detailCollectionService;

    private final LhAnnouncementSupplyCollectionService supplyCollectionService;

    private final VerifiedLhSupplyReplacementService replacementService;

    private final IngestExecutionOwnershipService ownershipService;

    public LhAnnouncementCollectionController(
            LhAnnouncementCatalogCollectionService catalogCollectionService,
            LhAnnouncementDetailCollectionService detailCollectionService,
            LhAnnouncementSupplyCollectionService supplyCollectionService,
            VerifiedLhSupplyReplacementService replacementService,
            IngestExecutionOwnershipService ownershipService
    ) {
        this.catalogCollectionService = catalogCollectionService;
        this.detailCollectionService = detailCollectionService;
        this.supplyCollectionService = supplyCollectionService;
        this.replacementService = replacementService;
        this.ownershipService = ownershipService;
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

    @PostMapping("/supplies/{pblancId}/verified-replacement")
    public ResponseEntity<ExternalDataCollectionReport> approveSupplyReplacement(
            @PathVariable String pblancId,
            @Valid @RequestBody VerifiedLhSupplyReplacementRequest request, Principal principal) {
        try (var ignored = ownershipService.acquire()) {
            long approvalId = replacementService.approve(pblancId, request, principal.getName());
            ExternalDataCollectionReport report;
            try {
                report = supplyCollectionService.refresh(pblancId);
            }
            catch (RuntimeException exception) {
                replacementService.finish(approvalId);
                throw exception;
            }
            boolean applied = replacementService.finish(approvalId);
            if (!applied && report.failedRequestCount() == 0 && report.successfulRequestCount() == 0) {
                throw new InvalidIngestRequestException(
                        "승인 대상 LH 공급 요청을 재조회하지 못했습니다."
                );
            }
            return responseOf(report);
        }
    }

    private ResponseEntity<ExternalDataCollectionReport> responseOf(ExternalDataCollectionReport report) {
        if (report.failedRequestCount() > 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(report);
        }
        return ResponseEntity.ok(report);
    }
}
