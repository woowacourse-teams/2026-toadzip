package com.toadzip.backend.ingest.lh.source;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LhCatalogSourceRepository extends JpaRepository<LhCatalogSource, Long> {

	List<LhCatalogSource> findAllByOrderBySourceOrderAscIdAsc();

}
