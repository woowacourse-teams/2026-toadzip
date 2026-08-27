package com.toadzip.backend.housing.controller;

import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.service.HousingComplexQueryService;

@RestController
@RequestMapping("/api/v1/complexes")
public class HousingComplexController {

    private final HousingComplexQueryService queryService;

    public HousingComplexController(HousingComplexQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<HousingComplexListResponse> getComplexes(
            @Valid @ParameterObject @ModelAttribute HousingComplexSearchRequest request,
            @RequestParam(defaultValue = "LATEST_ANNOUNCEMENT") ComplexSort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return new ApiResponse<>(queryService.getComplexes(request, sort, cursor, size));
    }

    @GetMapping("/{complexId}")
    public ApiResponse<HousingComplexDetailResponse> getComplex(
            @PathVariable long complexId
    ) {
        return new ApiResponse<>(queryService.getComplex(complexId));
    }

    @GetMapping("/map")
    public ApiResponse<HousingComplexMapResponse> getComplexesForMap(
            @Valid @ParameterObject @ModelAttribute HousingComplexSearchRequest request
    ) {
        return new ApiResponse<>(queryService.getComplexesForMap(request));
    }
}
