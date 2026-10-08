package com.toadzip.backend.ingest.correction.repository;

import com.toadzip.backend.ingest.correction.domain.IngestCorrectionChange;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestCorrectionChangeRepository extends JpaRepository<IngestCorrectionChange, Long> {
    List<IngestCorrectionChange> findByTargetOrderByIdDesc(String target, Pageable page);
}
