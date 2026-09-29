package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.AnnouncementViewRequest;
import com.toadzip.backend.announcement.dto.response.AnnouncementViewResponse;
import com.toadzip.backend.announcement.service.AnnouncementViewService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnnouncementViewController {

    private final AnnouncementViewService service;

    public AnnouncementViewController(AnnouncementViewService service) {
        this.service = service;
    }

    @PostMapping("/{announcementId}/views")
    public ResponseEntity<ApiResponse<AnnouncementViewResponse>> recordView(
            @PathVariable long announcementId, @Valid @RequestBody AnnouncementViewRequest request
    ) {
        long count = service.recordView(announcementId, UUID.fromString(request.viewerId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(new AnnouncementViewResponse(count)));
    }
}
