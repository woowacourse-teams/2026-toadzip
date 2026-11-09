package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
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

    public List<MyHomeAnnouncementSourceView> findAll() {
        return project(sources.findAll());
    }

    private List<MyHomeAnnouncementSourceView> project(
            List<MyHomeAnnouncementSource> bundles
    ) {
        List<MyHomeAnnouncementSourceView> result = new ArrayList<>();
        bundles.forEach(bundle -> bundle.getRows().forEach(row -> result.add(MyHomeAnnouncementSourceView.of(row))));
        result.sort(Comparator.comparing(MyHomeAnnouncementSourceView::isActive).reversed()
                .thenComparing(MyHomeAnnouncementSourceView::getCollectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MyHomeAnnouncementSourceView::getId));
        var distinct = new LinkedHashMap<Object, MyHomeAnnouncementSourceView>();
        result.stream().collect(Collectors.groupingBy(MyHomeAnnouncementSourceView::getSourceKey,
                        LinkedHashMap::new, Collectors.toList())).values().stream()
                .flatMap(group -> MyHomeAnnouncementCurrentSources
                        .select(group).stream())
                .forEach(row -> distinct.putIfAbsent(row.snapshot(), row));
        return List.copyOf(distinct.values());
    }

    public List<MyHomeAnnouncementSourceView> findAllByOrderByIdAsc() {
        return findAll().stream().sorted(Comparator.comparing(MyHomeAnnouncementSourceView::getId)).toList();
    }

    public List<MyHomeAnnouncementSourceView> findAllByPblancIdOrderByIdAsc(String id) {
        return findAllByPblancIdInOrderByIdAsc(List.of(id));
    }

    public List<MyHomeAnnouncementSourceView> findAllByPblancIdInOrderByIdAsc(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return project(sources.findAllByPblancIdIn(ids)).stream()
                .sorted(Comparator.comparing(MyHomeAnnouncementSourceView::getId)).toList();
    }

    public List<MyHomeAnnouncementSourceView> findByIdGreaterThanOrderByIdAsc(Long id, Pageable page) {
        return rows.findByIdGreaterThanOrderByIdAsc(id, page).stream()
                .map(MyHomeAnnouncementSourceView::of)
                .toList();
    }
}
