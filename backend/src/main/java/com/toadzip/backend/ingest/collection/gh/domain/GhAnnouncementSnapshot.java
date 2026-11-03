package com.toadzip.backend.ingest.collection.gh.domain;

import java.time.LocalDate;

public record GhAnnouncementSnapshot(
        GhAnnouncementBoard board, String pbancNo, String kindCode, String title, String supplyType,
        String region, LocalDate postedDate, LocalDate closingDate, String status,
        String originalUrl, String listUrl, String bodyText, String attachments
) {

    public GhAnnouncementSnapshot {
        if (board == null || pbancNo == null || !pbancNo.matches("[1-9][0-9]*")
                || kindCode == null || !kindCode.matches("[0-9]{2}")
                || title == null || title.isBlank() || supplyType == null || supplyType.isBlank()
                || region == null || postedDate == null
                || bodyText == null || attachments == null || attachments.isBlank()) {
            throw new IllegalArgumentException("GH 공고 원천의 게시판·식별자·제목·유형·지역·게시일·본문·첨부 정보는 필수입니다.");
        }
    }

    public String sourceKey() {
        return "GH:" + pbancNo;
    }
}
