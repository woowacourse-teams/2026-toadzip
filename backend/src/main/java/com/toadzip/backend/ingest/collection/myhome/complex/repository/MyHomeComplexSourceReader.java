package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyHomeComplexSourceReader {

    private final MyHomeComplexCollectionRepository sources;

    public List<MyHomeComplexSourceView> findAll() {
        return project(sources.findAll());
    }

    private List<MyHomeComplexSourceView> project(
            List<MyHomeComplexSource> bundles
    ) {
        List<MyHomeComplexSourceView> result = new ArrayList<>();
        bundles.forEach(bundle -> bundle.getRows().forEach(row -> result.add(MyHomeComplexSourceView.of(row))));
        var distinct = new LinkedHashMap<Object, MyHomeComplexSourceView>();
        result.forEach(row -> distinct.putIfAbsent(row.snapshot(), row));
        return List.copyOf(distinct.values());
    }

    public List<MyHomeComplexSourceView> findAllByHsmpSnIn(Collection<Long> identifiers) {
        if (identifiers.isEmpty()) {
            return List.of();
        }
        return project(sources.findAllByHsmpSnIn(identifiers));
    }

    public List<String> findDistinctRoadAddresses() {
        return findAll().stream().map(MyHomeComplexSourceView::getRnAdres)
                .filter(Objects::nonNull).distinct().toList();
    }
}
