package com.toadzip.backend.ingest.collection.domain;

import java.time.LocalDate;

public record ShAnnouncementSnapshot(
        String seq, String title, String department, LocalDate registeredDate,
        String originalUrl, String listUrl, String rawListHtml, String rawDetailHtml
) {

    public ShAnnouncementSnapshot {
        if (seq == null || !seq.matches("[1-9][0-9]*") || title == null || title.isBlank()
                || registeredDate == null
                || rawListHtml == null || rawListHtml.isBlank() || rawDetailHtml == null || rawDetailHtml.isBlank()) {
            throw new IllegalArgumentException("SH 공고 원천의 식별자·제목·등록일·원문은 필수입니다.");
        }
    }

    public String sourceKey() {
        return "SH:m_247:" + seq;
    }
}
