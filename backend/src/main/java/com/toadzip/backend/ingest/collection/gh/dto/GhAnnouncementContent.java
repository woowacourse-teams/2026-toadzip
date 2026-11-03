package com.toadzip.backend.ingest.collection.gh.dto;

import java.time.LocalDate;

public record GhAnnouncementContent(
        String title, String supplyType, LocalDate postedDate, String bodyText, String attachments
) {
}
