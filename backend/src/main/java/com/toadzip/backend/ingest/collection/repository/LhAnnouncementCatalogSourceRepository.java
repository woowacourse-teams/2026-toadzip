package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementCatalogSourceRepository extends JpaRepository<LhAnnouncementCatalogSource, Long> {

    List<LhAnnouncementCatalogSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<LhAnnouncementCatalogSource> findAllByPanIdIn(Collection<String> panIds);
}
