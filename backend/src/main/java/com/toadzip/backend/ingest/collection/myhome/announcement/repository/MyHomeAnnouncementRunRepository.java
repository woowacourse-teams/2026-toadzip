package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MyHomeAnnouncementRunRepository {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID runId, Instant completedAt) {
        return jdbc.update("INSERT INTO myhome_announcement_lifecycle_runs (execution_id, completed_at) "
                + "VALUES (?, ?) ON CONFLICT DO NOTHING", runId, java.sql.Timestamp.from(completedAt)) == 1;
    }
}
