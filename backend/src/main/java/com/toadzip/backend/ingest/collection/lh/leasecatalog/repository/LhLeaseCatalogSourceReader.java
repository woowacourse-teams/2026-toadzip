package com.toadzip.backend.ingest.collection.lh.leasecatalog.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhLeaseCatalogSourceReader {

    private final LhLeaseCatalogSourceRepository sources;

    public List<LhCatalogSource> findAllByOrderBySourceOrderAsc() {
        return sources.findByScopeKey("ALL").map(source -> source.getRows().stream()
                .map(row -> LhCatalogSource.read(row.getId(), row.getSourceOrder(), row.getCollectedAt(), row.snapshot()))
                .toList()).orElseGet(List::of);
    }
    public java.util.Optional<LhCatalogSource> findById(Long id) {
        return findAllByOrderBySourceOrderAsc().stream().filter(row -> id.equals(row.getId())).findFirst();
    }

}
