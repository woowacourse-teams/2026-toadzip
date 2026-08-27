package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingPreparationReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.service.MyHomeComplexMappingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/myhome/complex-mappings")
public class MyHomeComplexMappingController {

    private final MyHomeComplexMappingService mappingService;

    public MyHomeComplexMappingController(MyHomeComplexMappingService mappingService) {
        this.mappingService = mappingService;
    }

    @PostMapping
    public ResponseEntity<MyHomeComplexMappingReport> mapAll() {
        return ResponseEntity.ok(mappingService.mapAll());
    }

    @PostMapping("/candidates")
    public ResponseEntity<MyHomeComplexMappingPreparationReport> prepare() {
        return ResponseEntity.ok(mappingService.prepare());
    }

    @PostMapping("/batches")
    public ResponseEntity<MyHomeComplexMappingReport> mapNext(
            @RequestParam(defaultValue = "100") int batchSize
    ) {
        return ResponseEntity.ok(mappingService.mapNext(batchSize));
    }

    @GetMapping("/failures")
    public ResponseEntity<List<MyHomeComplexMappingFailureResponse>> findFailures() {
        return ResponseEntity.ok(mappingService.findFailures());
    }
}
