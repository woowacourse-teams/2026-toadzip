package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;

public interface LhAnnouncementDetailSourceRepository extends JpaRepository<LhAnnouncementDetailSource, Long> {

    boolean existsByPanId(String panId);

    void deleteByPanId(String panId);
}
