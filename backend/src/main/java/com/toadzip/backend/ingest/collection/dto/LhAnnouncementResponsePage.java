package com.toadzip.backend.ingest.collection.dto;

import java.util.List;

public record LhAnnouncementResponsePage<T>(
        List<T> items,
        int maximumDatasetRowCount
) {

    public LhAnnouncementResponsePage {
        items = List.copyOf(items);
        if (maximumDatasetRowCount < 0) {
            throw new IllegalArgumentException("dataset 행 수는 0 이상이어야 합니다.");
        }
    }
}
