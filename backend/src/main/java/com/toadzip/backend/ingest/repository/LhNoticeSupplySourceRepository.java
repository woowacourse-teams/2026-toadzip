package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.LhNoticeSupplySource;

public interface LhNoticeSupplySourceRepository extends JpaRepository<LhNoticeSupplySource, Long> {

    boolean existsByPanId(String panId);

    void deleteByPanId(String panId);
}
