package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyHomeComplexSourceReader {

    private final MyHomeComplexCollectionRepository sources;

    public List<MyHomeComplexSource> findAll() {
        return project(sources.findAll());
    }

    private List<MyHomeComplexSource> project(
            List<com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSource> bundles
    ) {
        List<MyHomeComplexSource> result = new ArrayList<>();
        bundles.forEach(bundle -> bundle.getRows().forEach(row -> result.add(MyHomeComplexSource.read(
                row.getId(), row.getSourceOrder(), row.getCollectedAt(), row.snapshot()))));
        var distinct = new LinkedHashMap<Object, MyHomeComplexSource>();
        result.forEach(row -> distinct.putIfAbsent(row.snapshot(), row));
        return List.copyOf(distinct.values());
    }

    public List<MyHomeComplexSource> findAllByHsmpSnIn(Collection<Long> identifiers) {
        if (identifiers.isEmpty()) {
            return List.of();
        }
        return project(sources.findAllByHsmpSnIn(identifiers));
    }

    public List<String> findDistinctRoadAddresses() {
        return findAll().stream().map(MyHomeComplexSource::getRnAdres)
                .filter(address -> address != null && !address.isBlank()).distinct().toList();
    }
}
