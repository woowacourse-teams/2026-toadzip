package com.toadzip.backend.ingest.controller;

import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.service.MyHomeAnnouncementMappingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
