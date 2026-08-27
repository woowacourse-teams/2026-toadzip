package com.toadzip.backend.housing.controller;

import io.swagger.v3.oas.annotations.Parameter;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.housing.domain.MapBounds;
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
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal southWestLat,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal southWestLng,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal northEastLat,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal northEastLng
    ) {
        MapBounds bounds = MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng);
        return new ApiResponse<>(queryService.getComplexes(bounds, cursor, size));
    }

    @GetMapping("/{complexId}")
    public ApiResponse<HousingComplexDetailResponse> getComplex(
            @PathVariable long complexId
    ) {
        return new ApiResponse<>(queryService.getComplex(complexId));
    }

    @GetMapping("/map")
    public ApiResponse<HousingComplexMapResponse> getComplexesForMap(
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal southWestLat,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal southWestLng,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal northEastLat,
            @Parameter(required = true)
            @RequestParam(required = false) BigDecimal northEastLng
    ) {
        MapBounds bounds = MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng);
        return new ApiResponse<>(queryService.getComplexesForMap(bounds));
    }
}
