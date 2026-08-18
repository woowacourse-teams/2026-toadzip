package com.toadzip.backend.ingest.lh.source;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LhNoticeDetailSourceRepository extends JpaRepository<LhNoticeDetailSource, Long> {

	boolean existsByPanId(String panId);

	void deleteByPanId(String panId);

	List<LhNoticeDetailSource> findByPanIdOrderBySourceOrderAscIdAsc(String panId);

	List<LhNoticeDetailSource> findAllByOrderByPanIdAscSourceOrderAscIdAsc();
}
