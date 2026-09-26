package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestExecutionOwnershipRepository {

    private final JdbcTemplate jdbcTemplate;

    public IngestExecutionOwnershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockAndVerify(UUID ownerId, long generation) {
        Boolean owns = jdbcTemplate.query("""
                SELECT owner_id, generation FROM ingest_execution_ownership WHERE id = 1 FOR SHARE
                """, result -> result.next()
                        && ownerId.equals(result.getObject("owner_id", UUID.class))
                        && generation == result.getLong("generation"));
        if (!Boolean.TRUE.equals(owns)) {
            throw new IngestOwnershipLostException();
        }
    }
}
