package com.toadzip.backend.ingest.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.service.LhNoticeDetailCollectionService;
import com.toadzip.backend.ingest.service.LhNoticeSupplyCollectionService;

@RestController
@RequestMapping("/api/admin/ingest/lh/notices")
public class LhNoticeCollectionController {

    private final LhNoticeDetailCollectionService detailCollectionService;

    private final LhNoticeSupplyCollectionService supplyCollectionService;

    public LhNoticeCollectionController(
            LhNoticeDetailCollectionService detailCollectionService,
            LhNoticeSupplyCollectionService supplyCollectionService
    ) {
        this.detailCollectionService = detailCollectionService;
        this.supplyCollectionService = supplyCollectionService;
    }

    @PostMapping("/details")
    public ExternalApiCollectionReport collectDetails() {
        return detailCollectionService.collect();
    }

    @PostMapping("/supplies")
    public ExternalApiCollectionReport collectSupplies() {
        return supplyCollectionService.collect();
    }
}
