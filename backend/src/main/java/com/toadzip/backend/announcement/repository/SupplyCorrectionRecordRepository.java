package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyCorrectionRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplyCorrectionRecordRepository extends JpaRepository<SupplyCorrectionRecord, Long> {
    Optional<SupplyCorrectionRecord> findByOperationId(UUID operationId);
}
