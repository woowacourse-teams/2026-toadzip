package com.toadzip.backend.ingest.collection.lh.controller;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.lh.supply.dto.VerifiedLhSupplyReplacementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.service.VerifiedLhSupplyReplacementService;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/lh/announcements")
@RequiredArgsConstructor
public class LhAnnouncementCollectionController {

    private final LhAnnouncementCatalogCollectionService catalogCollectionService;

    private final LhAnnouncementExternalCollectionService collectionService;

    private final VerifiedLhSupplyReplacementService replacementService;

    @PostMapping("/catalog")
    public ResponseEntity<ExternalDataCollectionReport> collectCatalog() {
        return responseOf(catalogCollectionService.collect());
    }

    @PostMapping("/details")
    public ResponseEntity<ExternalDataCollectionReport> collectDetails() {
        return responseOf(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
    }

    @PostMapping("/supplies")
    public ResponseEntity<ExternalDataCollectionReport> collectSupplies() {
        return responseOf(collectionService.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY));
    }

    @PostMapping("/details/{pblancId}/refresh")
    public ResponseEntity<ExternalDataCollectionReport> refreshDetails(@PathVariable String pblancId) {
        return responseOf(collectionService.refresh(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, pblancId));
    }

    @PostMapping("/supplies/{pblancId}/refresh")
    public ResponseEntity<ExternalDataCollectionReport> refreshSupplies(@PathVariable String pblancId) {
        return responseOf(collectionService.refresh(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, pblancId));
    }

    @PostMapping("/supplies/{pblancId}/verified-replacement")
    public ResponseEntity<ExternalDataCollectionReport> approveSupplyReplacement(
            @PathVariable String pblancId,
            @Valid @RequestBody VerifiedLhSupplyReplacementRequest request, Principal principal) {
        long approvalId = replacementService.approve(pblancId, request, principal.getName());
        ExternalDataCollectionReport report;
        try {
            report = collectionService.refresh(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, pblancId);
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

    private ResponseEntity<ExternalDataCollectionReport> responseOf(ExternalDataCollectionReport report) {
        if (report.failedRequestCount() > 0) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(report);
        }
        return ResponseEntity.ok(report);
    }
}
