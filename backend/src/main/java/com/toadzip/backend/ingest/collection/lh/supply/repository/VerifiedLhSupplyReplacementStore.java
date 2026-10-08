package com.toadzip.backend.ingest.collection.lh.supply.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VerifiedLhSupplyReplacementStore {

    private final JdbcClient jdbc;

    public VerifiedLhSupplyReplacementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long approve(String requestHash, String proposedFingerprint, String evidenceUrl,
            String reason, String approvedBy) {
        return jdbc.sql("""
                INSERT INTO verified_lh_supply_replacements
                    (request_hash, proposed_fingerprint, evidence_url, reason, approved_by, approved_at)
                VALUES (:requestHash, :fingerprint, :evidenceUrl, :reason, :approvedBy, CURRENT_TIMESTAMP)
                RETURNING id
                """).param("requestHash", requestHash).param("fingerprint", proposedFingerprint)
                .param("evidenceUrl", evidenceUrl).param("reason", reason).param("approvedBy", approvedBy)
                .query(Long.class).single();
    }

    public boolean consume(String requestHash, String proposedFingerprint) {
        return jdbc.sql("""
                UPDATE verified_lh_supply_replacements
                SET consumed_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id FROM verified_lh_supply_replacements
                    WHERE request_hash = :requestHash AND proposed_fingerprint = :fingerprint
                      AND consumed_at IS NULL AND revoked_at IS NULL
                    ORDER BY approved_at DESC, id DESC LIMIT 1 FOR UPDATE
                )
                """).param("requestHash", requestHash).param("fingerprint", proposedFingerprint)
                .update() == 1;
    }

    public boolean finish(long approvalId) {
        Boolean consumed = jdbc.sql("""
                SELECT consumed_at IS NOT NULL FROM verified_lh_supply_replacements WHERE id = :id
                """).param("id", approvalId).query(Boolean.class).single();
        if (consumed) {
            return true;
        }
        jdbc.sql("""
                UPDATE verified_lh_supply_replacements SET revoked_at = CURRENT_TIMESTAMP
                WHERE id = :id AND consumed_at IS NULL
                """).param("id", approvalId).update();
        return false;
    }
}
