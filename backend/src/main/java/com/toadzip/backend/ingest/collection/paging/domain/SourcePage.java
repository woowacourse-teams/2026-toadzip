package com.toadzip.backend.ingest.collection.paging.domain;

import java.util.List;

public record SourcePage<T>(int totalCount, List<T> rows) {

    public SourcePage {
        rows = List.copyOf(rows);
    }
}
