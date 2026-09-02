package com.toadzip.backend.housing.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.service.HousingMapQueryService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/complexes")
public class HousingMapController {

    private final HousingMapQueryService queryService;

    public HousingMapController(HousingMapQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/map")
    public ApiResponse<HousingMapResponse> getMap(
            @Valid @ParameterObject @ModelAttribute HousingComplexSearchRequest request,
            @RequestParam BigDecimal zoom,
            @RequestParam(required = false) Integer previousResolvedStage
    ) {
        return new ApiResponse<>(queryService.getMap(request, zoom, previousResolvedStage));
    }
}
