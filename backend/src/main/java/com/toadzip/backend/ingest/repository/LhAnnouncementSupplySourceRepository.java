package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;

public interface LhAnnouncementSupplySourceRepository extends JpaRepository<LhAnnouncementSupplySource, Long> {

    boolean existsByPanId(String panId);

    void deleteByPanId(String panId);
}
