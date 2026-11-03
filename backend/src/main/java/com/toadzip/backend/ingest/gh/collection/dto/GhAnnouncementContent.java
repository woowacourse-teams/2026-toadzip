package com.toadzip.backend.ingest.gh.collection.dto;

import java.time.LocalDate;
import java.util.List;

/** 읽지 못한 구획은 null이고 그 이름이 unreadParts에 담긴다. */
public record GhAnnouncementContent(
        String title, String supplyType, LocalDate postedDate,
        String districts, String reception, String notice, String otherNotes, String attachments,
        List<String> unreadParts
) {
}
