package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRegistrationRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportCreateResponse;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse;
import com.toadzip.backend.announcement.service.AdminAnnouncementImportRegistrationService;
import com.toadzip.backend.announcement.service.AdminAnnouncementImportValidationService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcement-imports")
public class AdminAnnouncementImportController {

    private final AdminAnnouncementImportValidationService validationService;
    private final AdminAnnouncementImportRegistrationService registrationService;

    public AdminAnnouncementImportController(
            AdminAnnouncementImportValidationService validationService,
            AdminAnnouncementImportRegistrationService registrationService
    ) {
        this.validationService = validationService;
        this.registrationService = registrationService;
    }

    @PostMapping("/validate")
    public ApiResponse<AdminAnnouncementImportValidationResponse> validate(
            @NotNull @RequestBody AdminAnnouncementImportRequest request
    ) {
        return new ApiResponse<>(validationService.validate(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminAnnouncementImportCreateResponse>> register(
            @Valid @RequestBody AdminAnnouncementImportRegistrationRequest request,
            Authentication authentication
    ) {
        AdminAnnouncementImportCreateResponse registered = registrationService.register(
                request,
                authentication.getName()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(registered));
    }
}
