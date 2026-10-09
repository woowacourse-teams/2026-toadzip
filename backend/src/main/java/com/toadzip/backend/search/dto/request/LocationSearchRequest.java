package com.toadzip.backend.search.dto.request;

import com.toadzip.backend.search.domain.LocationSearchType;

public record LocationSearchRequest(String query, LocationSearchType type, Integer page, Integer size) {
}
