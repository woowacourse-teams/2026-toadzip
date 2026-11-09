package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.CorrectionDraftContent;
import com.toadzip.backend.announcement.domain.StoredCorrectionDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리자별·공고별 PDF 보정 초안을 읽고 쓴다.
 * 쓰기는 조건부 한 문장이라 같은 revision을 본 두 요청 중 하나만 성공한다. 빈 결과는 revision 충돌이다.
 */
@Repository
@RequiredArgsConstructor
public class CorrectionDraftRepository {
    private static final String RETURNING = """
             RETURNING payload::text, base_snapshot_token, pdf_sha256, extraction_id, revision, updated_at
            """;
    private static final String FIND = """
            SELECT payload::text, base_snapshot_token, pdf_sha256, extraction_id, revision, updated_at
            FROM announcement_correction_drafts
            WHERE announcement_id = :announcementId AND admin_login_identifier = :admin
            """;
    private static final String INSERT = """
            INSERT INTO announcement_correction_drafts (announcement_id, admin_login_identifier, payload,
                base_snapshot_token, pdf_sha256, extraction_id, revision, created_at, updated_at)
            VALUES (:announcementId, :admin, CAST(:payload AS jsonb), :baseSnapshotToken, :pdfSha256, :extractionId,
                1, :now, :now)
            ON CONFLICT (announcement_id, admin_login_identifier) DO NOTHING
            """ + RETURNING;
    private static final String UPDATE = """
            UPDATE announcement_correction_drafts
            SET payload = CAST(:payload AS jsonb), base_snapshot_token = :baseSnapshotToken, pdf_sha256 = :pdfSha256,
                extraction_id = :extractionId, revision = revision + 1, updated_at = :now
            WHERE announcement_id = :announcementId AND admin_login_identifier = :admin
              AND revision = :expectedRevision
            """ + RETURNING;
    private static final String DELETE = """
            DELETE FROM announcement_correction_drafts
            WHERE announcement_id = :announcementId AND admin_login_identifier = :admin
              AND revision = :expectedRevision
            """;

    private final JdbcClient jdbc;

    public Optional<StoredCorrectionDraft> find(long announcementId, String admin) {
        return jdbc.sql(FIND).param("announcementId", announcementId).param("admin", admin)
                .query(CorrectionDraftRepository::stored).optional();
    }

    /** 이 관리자의 초안이 없을 때만 revision 1로 만든다. */
    public Optional<StoredCorrectionDraft> insertIfAbsent(long announcementId, String admin,
            CorrectionDraftContent content, Instant now) {
        return content(jdbc.sql(INSERT), content).param("announcementId", announcementId).param("admin", admin)
                .param("now", timestamp(now)).query(CorrectionDraftRepository::stored).optional();
    }

    /** 저장된 revision이 expectedRevision과 같을 때만 내용을 바꾸고 revision을 1 올린다. */
    public Optional<StoredCorrectionDraft> updateIfRevision(long announcementId, String admin, long expectedRevision,
            CorrectionDraftContent content, Instant now) {
        return content(jdbc.sql(UPDATE), content).param("announcementId", announcementId).param("admin", admin)
                .param("expectedRevision", expectedRevision).param("now", timestamp(now))
                .query(CorrectionDraftRepository::stored).optional();
    }

    public boolean deleteIfRevision(long announcementId, String admin, long expectedRevision) {
        return jdbc.sql(DELETE).param("announcementId", announcementId).param("admin", admin)
                .param("expectedRevision", expectedRevision).update() == 1;
    }

    private static JdbcClient.StatementSpec content(JdbcClient.StatementSpec statement, CorrectionDraftContent content) {
        return statement.param("payload", content.payload()).param("baseSnapshotToken", content.baseSnapshotToken())
                .param("pdfSha256", content.pdfSha256()).param("extractionId", content.extractionId());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static StoredCorrectionDraft stored(ResultSet row, int index) throws SQLException {
        var content = new CorrectionDraftContent(row.getString(1), row.getString(2), row.getString(3),
                row.getObject(4, Long.class));
        return new StoredCorrectionDraft(content, row.getLong(5), row.getObject(6, OffsetDateTime.class).toInstant());
    }
}
