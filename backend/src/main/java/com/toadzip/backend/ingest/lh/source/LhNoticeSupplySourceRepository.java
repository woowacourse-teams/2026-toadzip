package com.toadzip.backend.ingest.lh.source;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LhNoticeSupplySourceRepository extends JpaRepository<LhNoticeSupplySource, Long> {

	boolean existsByPanId(String panId);

	void deleteByPanId(String panId);

	List<LhNoticeSupplySource> findByPanIdOrderBySourceOrderAscIdAsc(String panId);

	List<LhNoticeSupplySource> findAllByOrderByPanIdAscSourceOrderAscIdAsc();
}
