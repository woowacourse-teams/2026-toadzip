package com.toadzip.backend.ingest.myhome.source;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexSourceRepository extends JpaRepository<MyHomeComplexSource, Long> {

	Optional<MyHomeComplexSource> findBySourceKey(String sourceKey);

}
