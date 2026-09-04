package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementCreateResponse;
import com.toadzip.backend.announcement.service.AdminAnnouncementRegistrationService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AdminAnnouncementRegistrationService registrationService;

    public AdminAnnouncementController(AdminAnnouncementRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminAnnouncementCreateResponse>> registerAnnouncement(
            @Valid @RequestBody AdminAnnouncementCreateRequest request
    ) {
        AdminAnnouncementCreateResponse registered = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(registered));
    }
}
