package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexMergeRepository extends JpaRepository<MyHomeComplexMerge, UUID> {
}
