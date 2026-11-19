package com.toadzip.backend.announcement.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PdfPublicationDecisions {
    private final JdbcClient jdbc;

    public List<Long> pending() {
        return jdbc.sql("""
                SELECT j.id FROM ai_jobs j LEFT JOIN pdf_publication_decisions d ON d.job_id = j.id
                WHERE j.request_source = 'BATCH' AND j.status = 'SUCCEEDED' AND j.input -> 'publication' IS NOT NULL
                  AND d.job_id IS NULL ORDER BY j.id LIMIT 10
                """).query(Long.class).list();
    }

    public boolean claim(long job) {
        return jdbc.sql("""
                SELECT id FROM ai_jobs WHERE id = ? AND request_source = 'BATCH' AND status = 'SUCCEEDED'
                  AND NOT EXISTS (SELECT 1 FROM pdf_publication_decisions WHERE job_id = ?)
                FOR UPDATE SKIP LOCKED
                """).params(job, job).query(Long.class).optional().isPresent();
    }

    public boolean isLatest(long job, long announcement) {
        return jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM ai_jobs WHERE announcement_id = ? AND id > ?)")
                .params(announcement, job).query(Boolean.class).single();
    }

    public void record(long job, String outcome, String reason, Long version, String eligibility) {
        jdbc.sql("""
                INSERT INTO pdf_publication_decisions(job_id, outcome, reason, published_version, eligibility_status)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (job_id) DO NOTHING
                """).params(job, outcome, reason, version, eligibility).update();
    }

    public List<Long> awaitingEligibility() {
        return jdbc.sql("SELECT job_id FROM pdf_publication_decisions WHERE eligibility_status = 'PENDING' ORDER BY job_id LIMIT 10")
                .query(Long.class).list();
    }

    public Optional<Long> claimEligibility(long job) {
        return jdbc.sql("""
                SELECT published_version FROM pdf_publication_decisions WHERE job_id = ?
                  AND eligibility_status = 'PENDING' FOR UPDATE SKIP LOCKED
                """).param(job).query(Long.class).optional();
    }

    public void finishEligibility(long job, String status, String reason) {
        jdbc.sql("UPDATE pdf_publication_decisions SET eligibility_status=?, reason=? WHERE job_id=? AND eligibility_status='PENDING'")
                .params(status, reason, job).update();
    }

    public record Decision(String outcome, String reason, String eligibilityStatus) {}

    public Optional<Decision> decision(long job) {
        return jdbc.sql("SELECT outcome, reason, eligibility_status FROM pdf_publication_decisions WHERE job_id = ?")
                .param(job).query(Decision.class).optional();
    }
}
