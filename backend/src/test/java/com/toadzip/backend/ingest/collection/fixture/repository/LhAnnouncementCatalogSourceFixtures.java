package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository.LhAnnouncementCatalogEntryRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("test")
@RequiredArgsConstructor
@Transactional
public class LhAnnouncementCatalogSourceFixtures {
    private final LhAnnouncementCatalogEntryRepository entries;
    private final JdbcClient jdbc;

    @Transactional(readOnly = true)
    public long count() {
        return entries.count();
    }

    public void deleteAllInBatch() {
        jdbc.sql("DELETE FROM lh_announcement_catalog_entries").update();
    }

    @Transactional(readOnly = true)
    public List<LhAnnouncementCatalogSourceView> findAllByPanIdInAndPresentInLatestCatalogTrue(
            Collection<String> ids) {
        return entries.findAllByPanIdInAndPresentInLatestCatalogTrue(ids).stream()
                .map(LhAnnouncementCatalogSourceView::of).toList();
    }
}
