package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.domain.IncomeStandard;
import com.toadzip.backend.eligibility.service.EligibilityRuleSetService;
import com.toadzip.backend.global.response.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EligibilityReferenceController {
    private final EligibilityRuleSetService management;

    public EligibilityReferenceController(EligibilityRuleSetService management) {
        this.management = management;
    }

    @GetMapping("/api/admin/basic-comparison/income-standards")
    public ApiResponse<List<IncomeStandard>> incomeStandards() {
        return new ApiResponse<>(management.incomeStandards());
    }
}
