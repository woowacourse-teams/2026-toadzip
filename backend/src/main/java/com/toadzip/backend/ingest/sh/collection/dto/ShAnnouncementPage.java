package com.toadzip.backend.ingest.sh.collection.dto;

import java.time.LocalDate;
import java.util.List;

public record ShAnnouncementPage(int page, int totalCount, List<Entry> entries) {

    public record Entry(String seq, String title, String department, LocalDate registeredDate) {
    }
}
