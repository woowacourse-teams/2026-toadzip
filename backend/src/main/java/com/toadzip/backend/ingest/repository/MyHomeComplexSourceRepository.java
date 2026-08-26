package com.toadzip.backend.ingest.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.MyHomeComplexSource;

public interface MyHomeComplexSourceRepository extends JpaRepository<MyHomeComplexSource, Long> {

    List<MyHomeComplexSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<MyHomeComplexSource> findAllByBrtcCodeAndSignguCode(String brtcCode, String signguCode);
}
