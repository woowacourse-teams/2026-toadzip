package com.toadzip.backend.search.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.search.dto.request.IntegratedSearchRequest;
import com.toadzip.backend.search.dto.response.IntegratedSearchResponse;
import com.toadzip.backend.search.service.IntegratedSearchService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/search", produces = MediaType.APPLICATION_JSON_VALUE)
public class IntegratedSearchController {

    private final IntegratedSearchService integratedSearchService;

    public IntegratedSearchController(IntegratedSearchService integratedSearchService) {
        this.integratedSearchService = integratedSearchService;
    }

    @GetMapping
    public ApiResponse<IntegratedSearchResponse> search(
            @ParameterObject @ModelAttribute IntegratedSearchRequest request
    ) {
        return new ApiResponse<>(integratedSearchService.search(request));
    }
}
