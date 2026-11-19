package com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogEntry;
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

    /** 정제까지 마친 뒤 행이 바뀌지 않은 목록 행의 키. 정제를 마친 적 없는 행은 빠진다. */
    @Query("SELECT source.sourceKey FROM LhAnnouncementCatalogEntry source WHERE source.sourceKey IN :keys "
            + "AND source.refinedAt IS NOT NULL AND source.refinedAt >= source.changedAt")
    List<String> findSourceKeysRefinedSinceChange(Collection<String> keys);

    /** 정제 트랜잭션 안에서 부른다. 정제가 되돌려지면 이 기록도 함께 되돌아간다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE LhAnnouncementCatalogEntry source SET source.refinedAt = :refinedAt WHERE source.sourceKey = :key")
    void markRefined(String key, Instant refinedAt);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE LhAnnouncementCatalogEntry source SET source.presentInLatestCatalog = false, "
            + "source.version = source.version + 1 WHERE source.presentInLatestCatalog = true "
            + "AND source.sourceKey NOT IN :keys")
    void markAbsent(Collection<String> keys);
}
