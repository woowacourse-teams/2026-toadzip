package com.toadzip.backend.ingest.lh.collection.leasecatalog.repository;

import com.toadzip.backend.ingest.lh.collection.leasecatalog.domain.LhLeaseCatalogSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhLeaseCatalogSourceRepository extends JpaRepository<LhLeaseCatalogSource, Long> {

    Optional<LhLeaseCatalogSource> findByScopeKey(String scopeKey);
}
