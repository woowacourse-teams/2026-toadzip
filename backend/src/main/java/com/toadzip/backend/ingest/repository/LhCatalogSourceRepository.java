package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.LhCatalogSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhCatalogSourceRepository extends JpaRepository<LhCatalogSource, Long> {

    List<LhCatalogSource> findAllByOrderBySourceOrderAsc();
}
