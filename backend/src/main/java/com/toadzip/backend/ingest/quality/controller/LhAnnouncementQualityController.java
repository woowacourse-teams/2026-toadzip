package com.toadzip.backend.ingest.quality.controller;

import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.service.LhAnnouncementQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/quality/lh-announcements")
public class LhAnnouncementQualityController {

    private final LhAnnouncementQualityService service;

    public LhAnnouncementQualityController(LhAnnouncementQualityService service) {
        this.service = service;
    }

    @GetMapping
    public LhAnnouncementQualityResponse snapshot() {
        return service.snapshot();
    }
}
