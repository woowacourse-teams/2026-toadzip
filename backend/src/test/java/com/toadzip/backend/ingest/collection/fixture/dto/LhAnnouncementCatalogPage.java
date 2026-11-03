package com.toadzip.backend.ingest.collection.fixture.dto;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import java.util.List;

public record LhAnnouncementCatalogPage(List<Entry> entries, int totalCount, String startDate, String endDate) {

    public LhAnnouncementCatalogPage {
        entries = List.copyOf(entries);
    }

    public record Entry(LhAnnouncementCatalogSnapshot snapshot, String rawPayload) {
    }
}
