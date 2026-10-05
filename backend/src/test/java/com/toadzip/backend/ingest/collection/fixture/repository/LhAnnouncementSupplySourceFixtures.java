package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
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
public class LhAnnouncementSupplySourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhAnnouncementSupplySource save(LhAnnouncementSupplySource row) {
        return rows.save("lh_announcement_supply_rows", row);
    }

    public LhAnnouncementSupplySource saveAndFlush(LhAnnouncementSupplySource row) {
        return save(row);
    }

    public List<LhAnnouncementSupplySource> saveAll(Iterable<LhAnnouncementSupplySource> sources) {
        List<LhAnnouncementSupplySource> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementSupplySource> findAll() {
        return jdbc.sql("""
                SELECT r.*, q.pan_id, q.request_hash
                FROM lh_announcement_supply_rows r JOIN lh_announcement_query_sources q ON q.id = r.source_id
                ORDER BY r.id
                """).query(LhAnnouncementSupplySource.class).list();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<LhAnnouncementSupplySource> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM lh_announcement_supply_rows").query(Long.class).single();
    }

    public void delete(LhAnnouncementSupplySource row) {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<LhAnnouncementSupplySource> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<LhAnnouncementSupplySource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(String pan, String hash) {
        return findAll().stream().filter(row -> pan.equals(row.getPanId())
                && java.util.Objects.equals(hash, row.getRequestHash())).toList();
    }

    public void deleteByPanIdAndRequestHash(String pan, String hash) {
        deleteAll(findAllByPanIdAndRequestHashOrderBySourceOrderAsc(pan, hash));
    }

}
