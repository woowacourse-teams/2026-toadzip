package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogEntry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementCatalogEntryRepository extends JpaRepository<LhAnnouncementCatalogEntry, Long> {

    List<LhAnnouncementCatalogEntry> findAllBySourceKeyIn(Collection<String> keys);

    List<LhAnnouncementCatalogEntry> findAllByPanIdIn(Collection<String> panIds);

    List<LhAnnouncementCatalogEntry> findAllByPanIdInAndPresentInLatestCatalogTrue(Collection<String> panIds);

    boolean existsByCollectedAtAfter(Instant collectedAt);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE LhAnnouncementCatalogEntry source SET source.presentInLatestCatalog = false, "
            + "source.version = source.version + 1 WHERE source.presentInLatestCatalog = true "
            + "AND source.sourceKey NOT IN :keys")
    void markAbsent(Collection<String> keys);
}
