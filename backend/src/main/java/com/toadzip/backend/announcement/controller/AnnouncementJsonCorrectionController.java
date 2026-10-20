package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection;
import com.toadzip.backend.announcement.service.AnnouncementJsonCorrectionService;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/announcements/{id}/json-corrections")
public class AnnouncementJsonCorrectionController {
    private final AnnouncementJsonCorrectionService service;
    private final IngestExecutionOwnershipService ownership;

    @GetMapping
    public ApiResponse<AnnouncementJsonCorrection.Workspace> workspace(@PathVariable long id) {
        return new ApiResponse<>(service.workspace(id));
    }

    @PostMapping("/preview")
    public ApiResponse<AnnouncementJsonCorrection.Preview> preview(@PathVariable long id,
            @Valid @RequestBody AnnouncementJsonCorrection.Request request) {
        return new ApiResponse<>(service.preview(id, request));
    }

    @PostMapping
    public ApiResponse<AnnouncementJsonCorrection.Workspace> apply(@PathVariable long id,
            @Valid @RequestBody AnnouncementJsonCorrection.Apply request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.apply(id, request, principal.getName()));
        }
    }
}
