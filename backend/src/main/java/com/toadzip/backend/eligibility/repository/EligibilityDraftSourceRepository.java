package com.toadzip.backend.eligibility.repository;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** AI 추출 초안에 쓰는 공고의 현재 상태: 모집공고일, 추출한 PDF의 첨부 주소. 신청 단위는 공고 공급 읽기 입구로 읽는다. */
@Repository
public class EligibilityDraftSourceRepository {
    private final JdbcClient jdbc;

    public EligibilityDraftSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocalDate> postedDate(long announcementId) {
        return jdbc.sql("SELECT posted_date FROM announcements WHERE id = ? AND NOT admin_deleted")
                .param(announcementId).query(LocalDate.class).optional();
    }

    public Optional<String> attachmentUrl(long announcementId, String fileName) {
        return jdbc.sql("""
                        SELECT file_url FROM announcement_attachments
                        WHERE announcement_id = ? AND file_name = ? AND file_url IS NOT NULL
                        ORDER BY display_order, id LIMIT 1
                        """)
                .params(announcementId, fileName).query(String.class).optional();
    }
}
