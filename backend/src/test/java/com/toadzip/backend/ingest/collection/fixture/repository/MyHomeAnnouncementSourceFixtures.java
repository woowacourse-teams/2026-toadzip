package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementRowRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final MyHomeAnnouncementRowRepository announcementRows;

    public MyHomeAnnouncementSourceView save(MyHomeAnnouncementSourceView row) {
        return rows.save("myhome_announcement_source_rows", row);
    }

    public MyHomeAnnouncementSourceView saveAndFlush(MyHomeAnnouncementSourceView row) {
        return save(row);
    }

    public List<MyHomeAnnouncementSourceView> saveAll(Iterable<MyHomeAnnouncementSourceView> sources) {
        List<MyHomeAnnouncementSourceView> result = new ArrayList<>();
        sources.forEach(row -> result.add(save(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAll() {
        return announcementRows.findAll(Sort.by("id")).stream().map(MyHomeAnnouncementSourceView::of).toList();
    }

    @Transactional(readOnly = true)
    public Optional<MyHomeAnnouncementSourceView> findById(Long id) {
        return findAll().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM myhome_announcement_source_rows").query(Long.class).single();
    }

    public void delete(MyHomeAnnouncementSourceView row) {
        jdbc.sql("DELETE FROM myhome_announcement_source_rows WHERE id = ?").param(row.getId()).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM myhome_announcement_source_rows").update();
    }

    public void deleteAllInBatch() {
        deleteAll();
    }

    public void deleteAll(Iterable<MyHomeAnnouncementSourceView> sources) {
        sources.forEach(this::delete);
    }

    @Transactional(readOnly = true)
    public void flush() {}

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAllBySourceKeyIn(Collection<String> keys) {
        return findAll().stream().filter(row -> keys.contains(row.getSourceKey())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAllByOrderByIdAsc() {
        return findAll();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAllByPblancIdOrderByIdAsc(String id) {
        return findAll().stream().filter(row -> id.equals(row.getPblancId())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAllByPblancIdInOrderByIdAsc(Collection<String> ids) {
        return findAll().stream().filter(row -> ids.contains(row.getPblancId())).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findByIdGreaterThanOrderByIdAsc(
            Long id, Pageable page) {
        return findAll().stream().filter(row -> row.getId() > id).skip(page.getOffset())
                .limit(page.getPageSize()).toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementSourceView> findAllActiveNotSeenInRun(String run) {
        return findAll().stream().filter(row -> row.isActive() && !run.equals(row.getLastSeenRunId())).toList();
    }

    @Transactional(readOnly = true)
    public int findMaxSourceOrder() {
        return findAll().stream().mapToInt(MyHomeAnnouncementSourceView::getSourceOrder).max().orElse(-1);
    }

}
