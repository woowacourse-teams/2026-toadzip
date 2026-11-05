package com.toadzip.backend.basiccomparison.controller;

import com.toadzip.backend.basiccomparison.domain.IncomeStandard;
import com.toadzip.backend.basiccomparison.service.ComparisonManagementService;
import com.toadzip.backend.global.response.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ComparisonReferenceController {
    private final ComparisonManagementService management;

    public ComparisonReferenceController(ComparisonManagementService management) {
        this.management = management;
    }

    @GetMapping("/api/admin/basic-comparison/income-standards")
    public ApiResponse<List<IncomeStandard>> incomeStandards() {
        return new ApiResponse<>(management.incomeStandards());
    }
}
