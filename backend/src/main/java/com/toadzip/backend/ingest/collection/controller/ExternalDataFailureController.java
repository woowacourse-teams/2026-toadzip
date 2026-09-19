package com.toadzip.backend.ingest.collection.controller;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionFailureResponse;
import com.toadzip.backend.ingest.collection.service.ExternalDataFailureQueryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/external-data-failures")
public class ExternalDataFailureController {

    private final ExternalDataFailureQueryService queryService;

    public ExternalDataFailureController(ExternalDataFailureQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<List<ExternalDataCollectionFailureResponse>> findPending() {
        return ResponseEntity.ok(queryService.findPending());
    }

    @GetMapping("/history")
    public ResponseEntity<List<ExternalDataCollectionFailureResponse>> findHistory() {
        return ResponseEntity.ok(queryService.findHistory());
    }
}
