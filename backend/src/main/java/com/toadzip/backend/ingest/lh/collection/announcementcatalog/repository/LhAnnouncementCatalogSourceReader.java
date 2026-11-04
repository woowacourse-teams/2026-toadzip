package com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
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

    /** 최신 공고목록에 있는 임대주택 공고를 공고 ID 순으로 읽는다. */
    public List<LhAnnouncementCatalogSourceView> findAllPresentRentalHousingAnnouncements() {
        return sources.findAll(Sort.by("panId", "id")).stream().map(LhAnnouncementCatalogSourceView::of)
                .filter(LhAnnouncementCatalogSourceView::isPresentInLatestCatalog)
                .filter(LhAnnouncementCatalogSourceView::isRentalHousingAnnouncement)
                .toList();
    }
}
