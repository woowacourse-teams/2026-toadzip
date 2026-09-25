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
    public int store(List<Entry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        Instant collectedAt = clock.instant();
        List<String> keys = entries.stream().map(entry -> entry.snapshot().sourceKey()).toList();
        Map<String, LhAnnouncementCatalogSource> stored = repository.findAllBySourceKeyIn(keys).stream()
                .collect(Collectors.toMap(LhAnnouncementCatalogSource::getSourceKey, Function.identity()));
        List<LhAnnouncementCatalogSource> sources = new ArrayList<>();
        for (Entry entry : entries) {
            LhAnnouncementCatalogSource source = stored.get(entry.snapshot().sourceKey());
            if (source == null) {
                source = LhAnnouncementCatalogSource.from(entry.snapshot(), entry.rawPayload(), collectedAt);
            }
            source.updateFrom(entry.snapshot(), entry.rawPayload(), collectedAt);
            sources.add(source);
        }
        repository.saveAll(sources);
        return sources.size();
    }
}
