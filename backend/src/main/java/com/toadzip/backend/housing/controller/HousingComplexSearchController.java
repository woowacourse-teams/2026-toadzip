package com.toadzip.backend.housing.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexSearchResponse;
import com.toadzip.backend.housing.service.HousingComplexQueryService;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/complexes/search")
public class HousingComplexSearchController {

    private final HousingComplexQueryService queryService;

    public HousingComplexSearchController(HousingComplexQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<HousingComplexSearchResponse> searchComplexes(
            @Valid @ParameterObject @ModelAttribute HousingComplexSearchRequest request,
            @RequestParam(defaultValue = "LATEST_ANNOUNCEMENT") ComplexSort sort,
            @RequestParam(defaultValue = "20") int size
    ) {
        return new ApiResponse<>(queryService.searchComplexes(request, sort, size));
    }
}
