package com.toadzip.backend.streetview.repository;

import com.toadzip.backend.streetview.domain.StreetViewPolicy;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StreetViewPolicyRepository extends JpaRepository<StreetViewPolicy, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select policy from StreetViewPolicy policy where policy.id = :id")
    Optional<StreetViewPolicy> findByIdForUpdate(@Param("id") long id);
}
