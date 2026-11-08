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
            SELECT target FROM InitialRegistrationTarget target
            WHERE target.status NOT IN (com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.EXISTING,
                com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.COMPLETED)
                AND target.housingComplexId IS NULL
                AND target.location.failure IS NOT NULL
            ORDER BY target.sourceComplexIdentifier
            """)
    Page<InitialRegistrationTarget> findLocationFailures(Pageable pageable);

    @Query("""
            SELECT coalesce(cast(target.location.failure AS string), 'READY') AS reason, count(target) AS total
            FROM InitialRegistrationTarget target
            WHERE target.status NOT IN (com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.EXISTING,
                com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus.COMPLETED)
                AND target.housingComplexId IS NULL
                AND target.location.checkedAt IS NOT NULL
            GROUP BY target.location.failure
            """)
    List<LocationCount> countLocations();

    interface LocationCount {
        String getReason();
        long getTotal();
    }

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
