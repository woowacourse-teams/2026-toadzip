package com.toadzip.backend.ingest.collection.sh.domain;

import java.time.LocalDate;

public record ShAnnouncementSnapshot(
        String seq, String title, String department, LocalDate registeredDate,
        String originalUrl, String listUrl, String bodyText, String attachments
) {

    public ShAnnouncementSnapshot {
        if (seq == null || !seq.matches("[1-9][0-9]*") || title == null || title.isBlank()
                || registeredDate == null
                || bodyText == null || attachments == null || attachments.isBlank()) {
            throw new IllegalArgumentException("SH 공고 원천의 식별자·제목·등록일·본문·첨부 정보는 필수입니다.");
        }
    }

    public String sourceKey() {
        return "SH:" + seq;
    }
}
