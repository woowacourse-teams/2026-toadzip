package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
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

    public List<LhAnnouncementCatalogSourceView> findAllByPanIdIn(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return sources.findAllByPanIdIn(ids).stream().map(LhAnnouncementCatalogSourceView::of).toList();
    }

    public List<LhAnnouncementCatalogSourceView> findAllByPanIdInAndPresentInLatestCatalogTrue(Collection<String> ids) {
        return findAllByPanIdIn(ids).stream().filter(LhAnnouncementCatalogSourceView::isPresentInLatestCatalog).toList();
    }
}
