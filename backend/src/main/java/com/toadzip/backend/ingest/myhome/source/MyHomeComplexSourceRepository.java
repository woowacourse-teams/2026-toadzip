package com.toadzip.backend.ingest.myhome.source;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexSourceRepository extends JpaRepository<MyHomeComplexSource, Long> {

	Optional<MyHomeComplexSource> findBySourceKey(String sourceKey);

	List<MyHomeComplexSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

	List<MyHomeComplexSource> findAllByBrtcCodeAndSignguCode(String brtcCode, String signguCode);

}
