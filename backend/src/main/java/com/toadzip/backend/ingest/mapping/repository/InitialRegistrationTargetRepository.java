package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InitialRegistrationTargetRepository extends JpaRepository<InitialRegistrationTarget, String> {

    @Query("""
            SELECT target FROM InitialRegistrationTarget target
            WHERE :status IS NULL OR target.status = :status
            ORDER BY target.sourceComplexIdentifier
            """)
    Page<InitialRegistrationTarget> findTargets(InitialRegistrationStatus status, Pageable pageable);

    @Query("""
            SELECT target.status AS status, count(target) AS total
            FROM InitialRegistrationTarget target GROUP BY target.status
            """)
    List<StatusCount> countByStatus();

    interface StatusCount {
        InitialRegistrationStatus getStatus();
        long getTotal();
    }
}
