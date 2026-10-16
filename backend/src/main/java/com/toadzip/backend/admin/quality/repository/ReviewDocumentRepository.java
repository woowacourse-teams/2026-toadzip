package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.ReviewDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDocumentRepository {
    private final JdbcClient jdbc;

    public ReviewDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int archive(QualityResource resource, long id, String sha256, String fileName, String sourceUrl,
            byte[] content, String actor) {
        return jdbc.sql("""
                INSERT INTO admin_review_documents(resource, resource_id, sha256, file_name, source_url, content, actor)
                VALUES (:resource, :id, :sha, :name, :url, :content, :actor)
                ON CONFLICT (resource, resource_id, sha256) DO NOTHING
                """).param("resource", resource.name()).param("id", id).param("sha", sha256)
                .param("name", fileName).param("url", sourceUrl).param("content", content)
                .param("actor", actor).update();
    }

    public List<ReviewDocument> documents(QualityResource resource, long id, int page) {
        return jdbc.sql("""
                SELECT sha256, file_name, source_url, octet_length(content) byte_count, actor, created_at
                FROM admin_review_documents WHERE resource = :resource AND resource_id = :id
                ORDER BY created_at DESC, sha256 LIMIT 20 OFFSET :offset
                """).param("resource", resource.name()).param("id", id).param("offset", (long) page * 20)
                .query((row, index) -> new ReviewDocument(row.getString("sha256"), row.getString("file_name"),
                        row.getString("source_url"), row.getLong("byte_count"), row.getString("actor"),
                        row.getTimestamp("created_at").toInstant())).list();
    }

    public Optional<Content> content(QualityResource resource, long id, String sha256) {
        return jdbc.sql("SELECT file_name, content FROM admin_review_documents "
                        + "WHERE resource = :resource AND resource_id = :id AND sha256 = :sha")
                .param("resource", resource.name()).param("id", id).param("sha", sha256)
                .query((row, index) -> new Content(row.getString(1), row.getBytes(2))).optional();
    }

    public boolean exists(QualityResource resource, long id, String sha256) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM admin_review_documents "
                        + "WHERE resource = :resource AND resource_id = :id AND sha256 = :sha)")
                .param("resource", resource.name()).param("id", id).param("sha", sha256)
                .query(Boolean.class).single();
    }

    public record Content(String fileName, byte[] bytes) { }
}
