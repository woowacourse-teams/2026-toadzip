package com.toadzip.backend.ingest.collection.sh.dto;

import java.time.LocalDate;
import java.util.List;

public record ShAnnouncementContent(
        String title, String department, LocalDate registeredDate,
        String bodyText, String attachments, List<String> unreadParts
) {
}
