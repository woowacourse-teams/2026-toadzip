package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.AnnouncementSearchRequest;
import com.toadzip.backend.announcement.domain.AnnouncementSort;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnnouncementController {

    private final AnnouncementQueryService announcementQueryService;

    public AnnouncementController(AnnouncementQueryService announcementQueryService) {
        this.announcementQueryService = announcementQueryService;
    }

    @GetMapping
    public ApiResponse<AnnouncementListResponse> getAnnouncements(
            @Valid @ModelAttribute AnnouncementSearchRequest request,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "LATEST") AnnouncementSort sort
    ) {
        if (sort == AnnouncementSort.LATEST_ASC) {
            return new ApiResponse<>(announcementQueryService.getAnnouncements(request, cursor, size, sort));
        }
        if (sort == AnnouncementSort.DEADLINE_DESC) {
            return new ApiResponse<>(announcementQueryService.getAnnouncementsByDeadline(request, cursor, size, sort));
        }
        if (sort == AnnouncementSort.DEADLINE) {
            return new ApiResponse<>(announcementQueryService.getAnnouncementsByDeadline(request, cursor, size));
        }
        return new ApiResponse<>(announcementQueryService.getAnnouncements(request, cursor, size));
    }

    public ApiResponse<AnnouncementListResponse> getAnnouncements(
            AnnouncementSearchRequest request, String cursor, int size
    ) {
        return getAnnouncements(request, cursor, size, AnnouncementSort.LATEST);
    }

    @GetMapping("/{announcementId}")
    public ApiResponse<AnnouncementDetailResponse> getAnnouncement(
            @PathVariable(name = "announcementId") long announcementId
    ) {
        return new ApiResponse<>(announcementQueryService.getAnnouncement(announcementId));
    }
}
