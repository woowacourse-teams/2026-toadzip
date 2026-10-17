package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.SaveReviewDraft;
import com.toadzip.backend.admin.quality.dto.SharedReviewDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SharedReviewDraftRepository {
    private final JdbcClient jdbc;

    public SharedReviewDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SharedReviewDraft> find(QualityResource resource, long id, String target) {
        return jdbc.sql("SELECT * FROM admin_review_drafts WHERE resource = :resource AND resource_id = :id "
                        + "AND target = :target").param("resource", resource.name()).param("id", id)
                .param("target", target).query((row, index) -> draft(row)).optional();
    }

    public Optional<SharedReviewDraft> save(QualityResource resource, long id, SaveReviewDraft request, String actor) {
        String sql = """
                INSERT INTO admin_review_drafts(resource, resource_id, target, revision, document_version,
                    sha256, source_url, pages, result_json, note, actor)
                VALUES (:resource, :id, :target, 1, :version, :sha, :url, :pages, :result, :note, :actor)
                ON CONFLICT (resource, resource_id, target) DO NOTHING RETURNING *
                """;
        if (request.expectedRevision() > 0) {
            sql = """
                    UPDATE admin_review_drafts SET revision = revision + 1, document_version = :version,
                        sha256 = :sha, source_url = :url, pages = :pages, result_json = :result,
                        note = :note, actor = :actor, updated_at = CURRENT_TIMESTAMP
                    WHERE resource = :resource AND resource_id = :id AND target = :target
                        AND revision = :revision RETURNING *
                    """;
        }
        var statement = jdbc.sql(sql).param("resource", resource.name()).param("id", id)
                .param("target", request.target()).param("version", request.documentVersion())
                .param("sha", request.sha256()).param("url", request.sourceUrl()).param("pages", request.pages())
                .param("result", request.resultJson()).param("note", request.note()).param("actor", actor);
        if (request.expectedRevision() > 0) {
            statement = statement.param("revision", request.expectedRevision());
        }
        return statement.query((row, index) -> draft(row)).optional();
    }

    private SharedReviewDraft draft(ResultSet row) throws SQLException {
        return new SharedReviewDraft(row.getString("target"), row.getLong("revision"),
                row.getLong("document_version"), row.getString("sha256"), row.getString("source_url"),
                row.getString("pages"), row.getString("result_json"), row.getString("note"), row.getString("actor"),
                row.getTimestamp("updated_at").toInstant());
    }
}
