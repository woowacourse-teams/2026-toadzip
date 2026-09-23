package com.toadzip.backend.ingest.collection.controller;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionFailureResponse;
import com.toadzip.backend.ingest.collection.service.ExternalDataFailureQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/external-data-failures")
public class ExternalDataFailureController {

    private final ExternalDataFailureQueryService queryService;

    public ExternalDataFailureController(ExternalDataFailureQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<List<ExternalDataCollectionFailureResponse>> findPending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(queryService.findPending(page, size));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ExternalDataCollectionFailureResponse>> findHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(queryService.findHistory(page, size));
    }
}
