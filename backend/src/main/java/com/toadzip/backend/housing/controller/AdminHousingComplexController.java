package com.toadzip.backend.housing.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexCreateResponse;
import com.toadzip.backend.housing.service.AdminHousingComplexRegistrationService;

@RestController
@RequestMapping("/api/admin/housing-complexes")
public class AdminHousingComplexController {

    private final AdminHousingComplexRegistrationService registrationService;

    public AdminHousingComplexController(AdminHousingComplexRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminHousingComplexCreateResponse>> registerHousingComplex(
            @Valid @RequestBody AdminHousingComplexCreateRequest request
    ) {
        AdminHousingComplexCreateResponse registered = registrationService.register(request);
        ApiResponse<AdminHousingComplexCreateResponse> response = new ApiResponse<>(registered);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
