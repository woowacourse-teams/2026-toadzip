package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.dto.OperationsOverview;
import com.toadzip.backend.admin.service.OperationsOverviewService;
import com.toadzip.backend.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AdminManagementApi
@RestController
@RequestMapping("/api/admin/operations")
public class OperationsOverviewController {
    private final OperationsOverviewService service;

    public OperationsOverviewController(OperationsOverviewService service) {
        this.service = service;
    }

    @GetMapping("/complexes")
    public ApiResponse<OperationsOverview.Complexes> complexes() {
        return new ApiResponse<>(service.complexes());
    }

    @GetMapping("/announcements")
    public ApiResponse<OperationsOverview.Announcements> announcements() {
        return new ApiResponse<>(service.announcements());
    }

    @GetMapping("/users")
    public ApiResponse<OperationsOverview.Users> users(@RequestParam(defaultValue = "7") int days) {
        return new ApiResponse<>(service.users(days));
    }

    @GetMapping("/collections")
    public ApiResponse<OperationsOverview.Collections> collections() {
        return new ApiResponse<>(service.collections());
    }
}
