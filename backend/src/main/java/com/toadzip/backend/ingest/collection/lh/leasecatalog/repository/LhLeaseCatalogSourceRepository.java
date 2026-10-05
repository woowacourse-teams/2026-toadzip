package com.toadzip.backend.ingest.collection.lh.leasecatalog.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhLeaseCatalogSourceRepository extends JpaRepository<LhLeaseCatalogSource, Long> {

    Optional<LhLeaseCatalogSource> findByScopeKey(String scopeKey);
}
