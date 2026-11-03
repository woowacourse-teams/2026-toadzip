package com.toadzip.backend.ingest.collection.gh.dto;

import java.time.LocalDate;
import java.util.List;

public record GhAnnouncementPage(int page, int totalCount, List<Entry> entries) {

    /** closingDate·status는 목록에 '-'로 표시되면 null이다. */
    public record Entry(
            String pbancNo, String kindCode, String supplyType, String title, String region,
            LocalDate postedDate, LocalDate closingDate, String status
    ) {
    }
}
