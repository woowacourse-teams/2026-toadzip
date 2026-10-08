package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class LhAnnouncementCatalogWriteLock {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        jdbc.queryForObject("SELECT id FROM lh_announcement_catalog_write_lock WHERE id = 1 FOR UPDATE", Long.class);
    }
}
