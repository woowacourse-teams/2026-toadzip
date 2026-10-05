package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyHomeAnnouncementSourceReader {

    private final MyHomeAnnouncementCollectionRepository sources;
    private final MyHomeAnnouncementRowRepository rows;

    public List<MyHomeAnnouncementSource> findAll() {
        return project(sources.findAll());
    }

    private List<MyHomeAnnouncementSource> project(
            List<com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSource> bundles
    ) {
        List<MyHomeAnnouncementSource> result = new ArrayList<>();
        bundles.forEach(bundle -> bundle.getRows().forEach(row -> result.add(MyHomeAnnouncementSource.read(
                row.getId(), row.getSourceOrder(), row.getCollectedAt(), row.getLastSeenRunId(),
                row.getConsecutiveMissCount(), row.isActive(), row.snapshot()))));
        result.sort(Comparator.comparing(MyHomeAnnouncementSource::isActive).reversed()
                .thenComparing(MyHomeAnnouncementSource::getCollectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MyHomeAnnouncementSource::getId));
        var distinct = new LinkedHashMap<Object, MyHomeAnnouncementSource>();
        result.stream().collect(Collectors.groupingBy(MyHomeAnnouncementSource::getSourceKey,
                        LinkedHashMap::new, Collectors.toList())).values().stream()
                .flatMap(group -> MyHomeAnnouncementCurrentSources
                        .select(group).stream())
                .forEach(row -> distinct.putIfAbsent(row.snapshot(), row));
        return List.copyOf(distinct.values());
    }

    public List<MyHomeAnnouncementSource> findAllByOrderByIdAsc() {
        return findAll().stream().sorted(Comparator.comparing(MyHomeAnnouncementSource::getId)).toList();
    }

    public List<MyHomeAnnouncementSource> findAllByPblancIdOrderByIdAsc(String id) {
        return findAllByPblancIdInOrderByIdAsc(List.of(id));
    }

    public List<MyHomeAnnouncementSource> findAllByPblancIdInOrderByIdAsc(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return project(sources.findAllByPblancIdIn(ids)).stream()
                .sorted(Comparator.comparing(MyHomeAnnouncementSource::getId)).toList();
    }

    public List<MyHomeAnnouncementSource> findByIdGreaterThanOrderByIdAsc(Long id, Pageable page) {
        return rows.findByIdGreaterThanOrderByIdAsc(id, page).stream()
                .map(row -> MyHomeAnnouncementSource.read(row.getId(), row.getSourceOrder(), row.getCollectedAt(),
                        row.getLastSeenRunId(), row.getConsecutiveMissCount(), row.isActive(), row.snapshot()))
                .toList();
    }
}
