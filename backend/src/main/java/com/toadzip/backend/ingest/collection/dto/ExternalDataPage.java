package com.toadzip.backend.ingest.collection.dto;

import java.util.List;

public record ExternalDataPage<T>(List<T> items, int totalCount) {

    public boolean completesCollection(int collectedCount, int pageSize) {
        if (totalCount >= 0) {
            return collectedCount >= totalCount;
        }
        return items.size() < pageSize;
    }
}
