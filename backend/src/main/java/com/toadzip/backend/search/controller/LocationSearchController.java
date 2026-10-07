package com.toadzip.backend.search.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.search.dto.request.LocationSearchRequest;
import com.toadzip.backend.search.dto.response.LocationSearchResponse;
import com.toadzip.backend.search.service.LocationSearchService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/locations/search", produces = MediaType.APPLICATION_JSON_VALUE)
public class LocationSearchController {

    private final LocationSearchService service;

    public LocationSearchController(LocationSearchService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<LocationSearchResponse> search(@ParameterObject @ModelAttribute LocationSearchRequest request) {
        return new ApiResponse<>(service.search(request));
    }
}
