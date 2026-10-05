package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
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
public class MyHomeAnnouncementSourceFixtures {
    private final CollectedSourceRows rows;
    private final JdbcClient jdbc;

    public MyHomeAnnouncementSource save(MyHomeAnnouncementSource row) {
        return rows.save("myhome_announcement_source_rows", row);
    }

    public MyHomeAnnouncementSource saveAndFlush(MyHomeAnnouncementSource row) {
        return save(row);
    }

    public List<MyHomeAnnouncementSource> saveAll(Iterable<MyHomeAnnouncementSource> sources) {
        List<MyHomeAnnouncementSource> result = new java.util.ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAll() {
        var sources = jdbc.sql("SELECT r.* FROM myhome_announcement_source_rows r ORDER BY r.id")
                .query(MyHomeAnnouncementSource.class).list();
        sources.forEach(source -> org.springframework.test.util.ReflectionTestUtils.setField(
                source, "sourceKey", MyHomeAnnouncementSource.sourceKeyOf(source.snapshot())));
        return sources;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<MyHomeAnnouncementSource> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM myhome_announcement_source_rows").query(Long.class).single();
    }

    public void delete(MyHomeAnnouncementSource row) {
        jdbc.sql("DELETE FROM myhome_announcement_source_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM myhome_announcement_source_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<MyHomeAnnouncementSource> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAllBySourceKeyIn(java.util.Collection<String> keys) {
        return findAll().stream().filter(row -> keys.contains(row.getSourceKey())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAllByOrderByIdAsc() {
        return findAll();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAllByPblancIdOrderByIdAsc(String id) {
        return findAll().stream().filter(row -> id.equals(row.getPblancId())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAllByPblancIdInOrderByIdAsc(java.util.Collection<String> ids) {
        return findAll().stream().filter(row -> ids.contains(row.getPblancId())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findByIdGreaterThanOrderByIdAsc(
            Long id, org.springframework.data.domain.Pageable page) {
        return findAll().stream().filter(row -> row.getId() > id).skip(page.getOffset())
                .limit(page.getPageSize()).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSource> findAllActiveNotSeenInRun(String run) {
        return findAll().stream().filter(row -> row.isActive() && !run.equals(row.getLastSeenRunId())).toList();
    }

    @Transactional(readOnly = true)
    public int findMaxSourceOrder() {
        return findAll().stream().mapToInt(MyHomeAnnouncementSource::getSourceOrder).max().orElse(-1);
    }

}
