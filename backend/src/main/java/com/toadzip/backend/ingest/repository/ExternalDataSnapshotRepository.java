package com.toadzip.backend.ingest.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;
import com.toadzip.backend.ingest.domain.ExternalDataSource;

public interface ExternalDataSnapshotRepository extends JpaRepository<ExternalDataSnapshot, Long> {

    List<ExternalDataSnapshot> findAllBySourceOrderByCollectedAtAscIdAsc(ExternalDataSource source);
}
