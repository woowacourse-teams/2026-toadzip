package com.toadzip.backend.ingest.collection.gh.domain;

import java.time.LocalDate;

/** 구획(districts·reception·notice·otherNotes·attachments)이 null이면 이번 수집에서 읽지 못해 저장값을 덮지 않는다. */
public record GhAnnouncementSnapshot(
        GhAnnouncementBoard board, String pbancNo, String kindCode, String title, String supplyType,
        String region, LocalDate postedDate, LocalDate closingDate, String status,
        String originalUrl, String listUrl,
        String districts, String reception, String notice, String otherNotes, String attachments
) {

    public GhAnnouncementSnapshot {
        if (board == null || pbancNo == null || !pbancNo.matches("[1-9][0-9]*")
                || kindCode == null || !kindCode.matches("[0-9]{2}")
                || title == null || title.isBlank() || supplyType == null || supplyType.isBlank()
                || region == null || postedDate == null || (attachments != null && attachments.isBlank())) {
            throw new IllegalArgumentException("GH 공고 원천의 게시판·식별자·제목·유형·지역·게시일은 필수입니다.");
        }
    }

    public GhAnnouncementSnapshot withDistricts(String districts) {
        return new GhAnnouncementSnapshot(board, pbancNo, kindCode, title, supplyType, region, postedDate, closingDate,
                status, originalUrl, listUrl, districts, reception, notice, otherNotes, attachments);
    }

    public String sourceKey() {
        return "GH:" + pbancNo;
    }
}
