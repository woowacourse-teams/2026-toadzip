package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/myhome/announcement-mappings")
public class MyHomeAnnouncementMappingController {

    private final MyHomeAnnouncementMappingService mappingService;

    public MyHomeAnnouncementMappingController(MyHomeAnnouncementMappingService mappingService) {
        this.mappingService = mappingService;
    }

    @PostMapping
    public ResponseEntity<MyHomeAnnouncementMappingReport> mapAll() {
        return ResponseEntity.ok(mappingService.mapAll());
    }

    @GetMapping("/failures")
    public ResponseEntity<List<MyHomeAnnouncementMappingFailureResponse>> findFailures() {
        return ResponseEntity.ok(mappingService.findFailures());
    }

    @GetMapping("/failures/page")
    public ResponseEntity<List<MyHomeAnnouncementMappingFailureResponse>> findFailurePage(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(mappingService.findFailures(page, size));
    }

    @GetMapping("/failures/history")
    public ResponseEntity<List<MyHomeAnnouncementMappingFailureResponse>> findFailureHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(mappingService.findFailureHistory(page, size));
    }
}
