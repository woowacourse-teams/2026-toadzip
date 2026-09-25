package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class LhAnnouncementCatalogStore {

    private final LhAnnouncementCatalogSourceRepository repository;
    private final Clock clock;

    @Transactional
    public StoreResult store(List<Entry> entries) {
        if (entries.isEmpty()) {
            repository.markAllAbsentFromLatestCatalog();
            return new StoreResult(0, 0, 0);
        }
        Instant collectedAt = clock.instant();
        List<String> keys = entries.stream().map(entry -> entry.snapshot().sourceKey()).toList();
        Map<String, LhAnnouncementCatalogSource> stored = repository.findAllBySourceKeyIn(keys).stream()
                .collect(Collectors.toMap(LhAnnouncementCatalogSource::getSourceKey, Function.identity()));
        repository.markAbsentFromLatestCatalog(keys);
        List<LhAnnouncementCatalogSource> sources = new ArrayList<>();
        int newRowCount = 0;
        int changedRowCount = 0;
        for (Entry entry : entries) {
            LhAnnouncementCatalogSource source = stored.get(entry.snapshot().sourceKey());
            if (source == null) {
                sources.add(LhAnnouncementCatalogSource.from(entry.snapshot(), entry.rawPayload(), collectedAt));
                newRowCount++;
                continue;
            }
            if (source.updateFrom(entry.snapshot(), entry.rawPayload(), collectedAt)) {
                changedRowCount++;
            }
            sources.add(source);
        }
        repository.saveAll(sources);
        return new StoreResult(sources.size(), newRowCount, changedRowCount);
    }

    public record StoreResult(int storedRowCount, int newRowCount, int changedRowCount) {

        public int unchangedRowCount() {
            return storedRowCount - newRowCount - changedRowCount;
        }
    }
}
