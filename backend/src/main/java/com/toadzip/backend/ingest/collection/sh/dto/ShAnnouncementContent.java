package com.toadzip.backend.ingest.collection.sh.dto;

import java.time.LocalDate;

public record ShAnnouncementContent(
        String title, String department, LocalDate registeredDate,
        String bodyText, String attachments
) {
}
