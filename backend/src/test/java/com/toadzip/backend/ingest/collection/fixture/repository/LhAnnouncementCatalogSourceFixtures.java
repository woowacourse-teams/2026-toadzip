package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("test")
@RequiredArgsConstructor
@Transactional
public class LhAnnouncementCatalogSourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhAnnouncementCatalogSource save(LhAnnouncementCatalogSource row) {
        return rows.save("lh_announcement_catalog_entries", row);
    }

    public LhAnnouncementCatalogSource saveAndFlush(LhAnnouncementCatalogSource row) {
        return save(row);
    }

    public List<LhAnnouncementCatalogSource> saveAll(Iterable<LhAnnouncementCatalogSource> sources) {
        List<LhAnnouncementCatalogSource> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementCatalogSource> findAll() {
        return jdbc.sql("SELECT r.* FROM lh_announcement_catalog_entries r ORDER BY r.id")
                .query(LhAnnouncementCatalogSource.class).list();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<LhAnnouncementCatalogSource> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM lh_announcement_catalog_entries").query(Long.class).single();
    }

    public void delete(LhAnnouncementCatalogSource row) {
        jdbc.sql("DELETE FROM lh_announcement_catalog_entries WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM lh_announcement_catalog_entries").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<LhAnnouncementCatalogSource> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<LhAnnouncementCatalogSource> findAllBySourceKeyIn(java.util.Collection<String> keys) {
        return findAll().stream().filter(row -> keys.contains(row.getSourceKey())).toList();
    }

    public void markAbsentFromLatestCatalog(List<String> keys) {
        jdbc.sql("UPDATE lh_announcement_catalog_entries SET present_in_latest_catalog = false "
                + "WHERE source_key NOT IN (:keys)").param("keys", keys).update();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementCatalogSource> findAllByPanIdIn(java.util.Collection<String> ids) {
        return findAll().stream().filter(row -> ids.contains(row.getPanId())).toList();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementCatalogSource> findAllByPanIdInAndPresentInLatestCatalogTrue(
            java.util.Collection<String> ids) {
        return findAllByPanIdIn(ids).stream().filter(LhAnnouncementCatalogSource::isPresentInLatestCatalog).toList();
    }

}
