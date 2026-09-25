package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementCatalogSourceRepository extends JpaRepository<LhAnnouncementCatalogSource, Long> {

    List<LhAnnouncementCatalogSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<LhAnnouncementCatalogSource> findAllByPanIdIn(Collection<String> panIds);

    List<LhAnnouncementCatalogSource> findAllByPanIdInAndPresentInLatestCatalogTrue(Collection<String> panIds);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE LhAnnouncementCatalogSource source SET source.presentInLatestCatalog = false "
            + "WHERE source.presentInLatestCatalog = true AND source.sourceKey NOT IN :sourceKeys")
    void markAbsentFromLatestCatalog(Collection<String> sourceKeys);
}
