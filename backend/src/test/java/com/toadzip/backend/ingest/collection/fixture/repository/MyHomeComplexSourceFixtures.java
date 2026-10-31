package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
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
public class MyHomeComplexSourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public MyHomeComplexSourceView save(MyHomeComplexSourceView row) {
        return rows.save("myhome_complex_source_rows", row);
    }

    public MyHomeComplexSourceView saveAndFlush(MyHomeComplexSourceView row) {
        return save(row);
    }

    public List<MyHomeComplexSourceView> saveAll(Iterable<MyHomeComplexSourceView> sources) {
        List<MyHomeComplexSourceView> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexSourceView> findAll() {
        var sources = jdbc.sql("SELECT r.* FROM myhome_complex_source_rows r ORDER BY r.id")
                .query(MyHomeComplexSourceView.class).list();
        sources.forEach(source -> org.springframework.test.util.ReflectionTestUtils.setField(
                source, "sourceKey", MyHomeComplexSourceView.sourceKeyOf(source.snapshot())));
        return sources;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<MyHomeComplexSourceView> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM myhome_complex_source_rows").query(Long.class).single();
    }

    public void delete(MyHomeComplexSourceView row) {
        jdbc.sql("DELETE FROM myhome_complex_source_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM myhome_complex_source_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<MyHomeComplexSourceView> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<MyHomeComplexSourceView> findAllBySourceKeyIn(java.util.Collection<String> keys) {
        return findAll().stream().filter(row -> keys.contains(row.getSourceKey())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexSourceView> findAllByHsmpSnIn(java.util.Collection<Long> ids) {
        return findAll().stream().filter(row -> ids.contains(row.getHsmpSn())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexSourceView> findAllByBrtcCodeAndSignguCode(String province, String district) {
        return findAll().stream().filter(row -> province.equals(row.getBrtcCode())
                && district.equals(row.getSignguCode())).toList();
    }

}
