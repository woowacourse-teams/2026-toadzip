package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementCatalogSourceReader {

    private final LhAnnouncementCatalogEntryRepository sources;

    public List<LhAnnouncementCatalogSource> findAllByPanIdIn(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var entries = sources.findAllByPanIdIn(ids);
        List<LhAnnouncementCatalogSource> result = new ArrayList<>();
        entries.forEach(row -> result.add(LhAnnouncementCatalogSource.read(row.getId(), row.getChangedAt(),
                row.getCollectedAt(), row.isPresentInLatestCatalog(), row.getRawPayload(), row.snapshot())));
        return List.copyOf(result);
    }

    public List<LhAnnouncementCatalogSource> findAllByPanIdInAndPresentInLatestCatalogTrue(Collection<String> ids) {
        return findAllByPanIdIn(ids).stream().filter(LhAnnouncementCatalogSource::isPresentInLatestCatalog).toList();
    }
}
