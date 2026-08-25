package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.LhNoticeDetailSource;

public interface LhNoticeDetailSourceRepository extends JpaRepository<LhNoticeDetailSource, Long> {

    boolean existsByPanId(String panId);

    void deleteByPanId(String panId);
}
