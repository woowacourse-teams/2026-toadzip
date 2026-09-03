package com.toadzip.backend.search.dto.response;

import com.toadzip.backend.search.domain.SearchType;

public record SearchFailureResponse(SearchType type, String message) {
}
