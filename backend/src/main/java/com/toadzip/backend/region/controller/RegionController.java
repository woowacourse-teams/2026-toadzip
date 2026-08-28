package com.toadzip.backend.region.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.region.dto.response.RegionSearchResponse;
import com.toadzip.backend.region.service.RegionQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/regions", produces = MediaType.APPLICATION_JSON_VALUE)
public class RegionController {

    private final RegionQueryService regionQueryService;

    public RegionController(RegionQueryService regionQueryService) {
        this.regionQueryService = regionQueryService;
    }

    @GetMapping
    public ApiResponse<RegionSearchResponse> searchRegions(
            @RequestParam
            @NotBlank(message = "검색어를 입력해 주세요.")
            @Size(max = 50, message = "검색어는 50자 이하여야 합니다.")
            String keyword
    ) {
        return new ApiResponse<>(regionQueryService.searchRegions(keyword));
    }
}
