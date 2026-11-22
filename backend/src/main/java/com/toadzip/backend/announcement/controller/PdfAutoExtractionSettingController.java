package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.admin.controller.AdminManagementApi;
import com.toadzip.backend.announcement.dto.request.PdfAutoExtractionChangeRequest;
import com.toadzip.backend.announcement.dto.response.PdfAutoExtractionStatus;
import com.toadzip.backend.announcement.service.PdfAutoExtractionSettingService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AdminManagementApi
@RequiredArgsConstructor
@RequestMapping("/api/admin/operations/pdf-auto-extraction")
public class PdfAutoExtractionSettingController {
    private final PdfAutoExtractionSettingService service;

    @GetMapping
    public ApiResponse<PdfAutoExtractionStatus> status() {
        return new ApiResponse<>(service.status());
    }

    @PutMapping
    public ApiResponse<PdfAutoExtractionStatus> change(@Valid @RequestBody PdfAutoExtractionChangeRequest request,
            Authentication authentication) {
        return new ApiResponse<>(service.change(request.enabled(), authentication.getName()));
    }
}
