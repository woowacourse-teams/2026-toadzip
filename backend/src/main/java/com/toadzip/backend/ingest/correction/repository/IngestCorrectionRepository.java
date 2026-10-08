package com.toadzip.backend.ingest.correction.repository;

import com.toadzip.backend.ingest.correction.domain.IngestCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestCorrectionRepository extends JpaRepository<IngestCorrection, String> {
}
