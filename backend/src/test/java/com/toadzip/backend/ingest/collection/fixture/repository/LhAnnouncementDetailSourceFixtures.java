package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
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
public class LhAnnouncementDetailSourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhAnnouncementDetailSource save(LhAnnouncementDetailSource row) {
        return rows.save("lh_announcement_detail_rows", row);
    }

    public LhAnnouncementDetailSource saveAndFlush(LhAnnouncementDetailSource row) {
        return save(row);
    }

    public List<LhAnnouncementDetailSource> saveAll(Iterable<LhAnnouncementDetailSource> sources) {
        List<LhAnnouncementDetailSource> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementDetailSource> findAll() {
        return jdbc.sql("""
                SELECT r.*, q.pan_id, q.request_hash
                FROM lh_announcement_detail_rows r JOIN lh_announcement_query_sources q ON q.id = r.source_id
                ORDER BY r.id
                """).query(LhAnnouncementDetailSource.class).list();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<LhAnnouncementDetailSource> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM lh_announcement_detail_rows").query(Long.class).single();
    }

    public void delete(LhAnnouncementDetailSource row) {
        jdbc.sql("DELETE FROM lh_announcement_detail_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM lh_announcement_detail_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<LhAnnouncementDetailSource> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<LhAnnouncementDetailSource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(String pan, String hash) {
        return findAll().stream().filter(row -> pan.equals(row.getPanId())
                && java.util.Objects.equals(hash, row.getRequestHash())).toList();
    }

    public void deleteByPanIdAndRequestHash(String pan, String hash) {
        deleteAll(findAllByPanIdAndRequestHashOrderBySourceOrderAsc(pan, hash));
    }

}
