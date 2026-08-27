package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.service.MyHomeComplexMappingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/failures")
    public ResponseEntity<List<MyHomeComplexMappingFailureResponse>> findFailures() {
        return ResponseEntity.ok(mappingService.findFailures());
    }
}
