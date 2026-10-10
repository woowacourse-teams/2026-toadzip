package com.toadzip.backend.search.repository;

import java.util.List;

public record LocationSearchPage(List<LocationSearchItem> items, boolean hasNext, Long totalCount) {
    public LocationSearchPage {
        items = List.copyOf(items);
    }
}
