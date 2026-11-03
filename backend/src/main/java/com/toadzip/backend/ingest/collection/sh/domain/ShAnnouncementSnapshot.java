package com.toadzip.backend.ingest.collection.sh.domain;

import java.time.LocalDate;

/** bodyText·attachments가 null이면 이번 수집에서 읽지 못한 부분이다. 빈 본문은 "", 첨부 없음은 "[]"다. */
public record ShAnnouncementSnapshot(
        String seq, String title, String department, LocalDate registeredDate,
        String originalUrl, String listUrl, String bodyText, String attachments
) {

    public ShAnnouncementSnapshot {
        if (seq == null || !seq.matches("[1-9][0-9]*") || title == null || title.isBlank()
                || registeredDate == null || department == null
                || (attachments != null && attachments.isBlank())) {
            throw new IllegalArgumentException("SH 공고 원천의 식별자·제목·담당부서·등록일은 필수입니다.");
        }
    }

    public String sourceKey() {
        return "SH:" + seq;
    }
}
