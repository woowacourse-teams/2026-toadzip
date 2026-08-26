package com.toadzip.backend.housing.controller;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.service.HousingComplexQueryService;

@RestController
@RequestMapping("/api/v1/complexes")
public class HousingComplexController {

    private final HousingComplexQueryService queryService;

    public HousingComplexController(HousingComplexQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/map")
    public ApiResponse<HousingComplexMapResponse> getComplexesForMap(
            @RequestParam(required = false) BigDecimal southWestLat,
            @RequestParam(required = false) BigDecimal southWestLng,
            @RequestParam(required = false) BigDecimal northEastLat,
            @RequestParam(required = false) BigDecimal northEastLng
    ) {
        MapBounds bounds = MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng);
        return new ApiResponse<>(queryService.getComplexesForMap(bounds));
    }
}
