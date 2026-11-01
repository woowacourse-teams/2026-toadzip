package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("test")
@RequiredArgsConstructor
@Transactional
public class LhAnnouncementSupplySourceFixtures {
    private static final DataClassRowMapper<LhAnnouncementSupplySourceSnapshot> SNAPSHOTS = new DataClassRowMapper<>(LhAnnouncementSupplySourceSnapshot.class);

    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhAnnouncementSupplySourceView save(LhAnnouncementSupplySourceView row) {
        return rows.save("lh_announcement_supply_rows", row);
    }

    public LhAnnouncementSupplySourceView saveAndFlush(LhAnnouncementSupplySourceView row) {
        return save(row);
    }

    public List<LhAnnouncementSupplySourceView> saveAll(Iterable<LhAnnouncementSupplySourceView> sources) {
        List<LhAnnouncementSupplySourceView> result = new ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementSupplySourceView> findAll() {
        return jdbc.sql("""
                SELECT r.*, q.pan_id, q.request_hash
                FROM lh_announcement_supply_rows r JOIN lh_announcement_query_sources q ON q.id = r.source_id
                ORDER BY r.id
                """).query(this::read).list();
    }

    @Transactional(readOnly = true)
    public Optional<LhAnnouncementSupplySourceView> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM lh_announcement_supply_rows").query(Long.class).single();
    }

    public void delete(LhAnnouncementSupplySourceView row) {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<LhAnnouncementSupplySourceView> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<LhAnnouncementSupplySourceView> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(String pan, String hash) {
        return findAll().stream().filter(row -> pan.equals(row.getPanId())
                && Objects.equals(hash, row.getRequestHash())).toList();
    }

    public void deleteByPanIdAndRequestHash(String pan, String hash) {
        deleteAll(findAllByPanIdAndRequestHashOrderBySourceOrderAsc(pan, hash));
    }

    private LhAnnouncementSupplySourceView read(ResultSet row, int number) throws SQLException {
        return LhAnnouncementSupplySourceView.read(row.getLong("id"), row.getInt("source_order"), row.getString("pan_id"),
                row.getString("request_hash"), collectedAt(row),
                SNAPSHOTS.mapRow(row, number));
    }

    private static Instant collectedAt(ResultSet row) throws SQLException {
        OffsetDateTime collectedAt = row.getObject("collected_at", OffsetDateTime.class);
        return collectedAt == null ? null : collectedAt.toInstant();
    }
}
