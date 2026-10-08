package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
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
public class LhLeaseCatalogSourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public LhCatalogSource save(LhCatalogSource row) {
        return rows.save("lh_lease_catalog_source_rows", row);
    }

    public LhCatalogSource saveAndFlush(LhCatalogSource row) {
        return save(row);
    }

    public List<LhCatalogSource> saveAll(Iterable<LhCatalogSource> sources) {
        List<LhCatalogSource> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<LhCatalogSource> findAll() {
        return jdbc.sql("SELECT r.* FROM lh_lease_catalog_source_rows r ORDER BY r.id")
                .query(LhCatalogSource.class).list();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<LhCatalogSource> findById(Long id) {
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

}
