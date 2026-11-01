package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
public class LhLeaseCatalogSourceFixtures {
    private static final DataClassRowMapper<LhCatalogSourceSnapshot> SNAPSHOTS = new DataClassRowMapper<>(LhCatalogSourceSnapshot.class);

    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhCatalogSource save(LhCatalogSource row) {
        return rows.save("lh_lease_catalog_source_rows", row);
    }

    public LhCatalogSource saveAndFlush(LhCatalogSource row) {
        return save(row);
    }

    public List<LhCatalogSource> saveAll(Iterable<LhCatalogSource> sources) {
        List<LhCatalogSource> result = new ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhCatalogSource> findAll() {
        return jdbc.sql("SELECT r.* FROM lh_lease_catalog_source_rows r ORDER BY r.id")
                .query(this::read).list();
    }

    @Transactional(readOnly = true)
    public Optional<LhCatalogSource> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM lh_lease_catalog_source_rows").query(Long.class).single();
    }

    public void delete(LhCatalogSource row) {
        jdbc.sql("DELETE FROM lh_lease_catalog_source_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM lh_lease_catalog_source_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<LhCatalogSource> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<LhCatalogSource> findAllByOrderBySourceOrderAsc() {
        return findAll();
    }

    private LhCatalogSource read(ResultSet row, int number) throws SQLException {
        return LhCatalogSource.read(row.getLong("id"), row.getInt("source_order"),
                collectedAt(row), SNAPSHOTS.mapRow(row, number));
    }

    private static Instant collectedAt(ResultSet row) throws SQLException {
        OffsetDateTime collectedAt = row.getObject("collected_at", OffsetDateTime.class);
        return collectedAt == null ? null : collectedAt.toInstant();
    }
}
