package com.toadzip.backend.ingest.collection.dto;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import java.util.List;

public record LhAnnouncementCatalogPage(List<Entry> entries, int totalCount, String startDate, String endDate) {

    public LhAnnouncementCatalogPage {
        entries = List.copyOf(entries);
    }

    public record Entry(LhAnnouncementCatalogSnapshot snapshot, String rawPayload) {
    }
}
