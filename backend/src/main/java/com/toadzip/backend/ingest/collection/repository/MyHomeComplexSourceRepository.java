package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MyHomeComplexSourceRepository extends JpaRepository<MyHomeComplexSource, Long> {

    List<MyHomeComplexSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<MyHomeComplexSource> findAllByBrtcCodeAndSignguCode(String brtcCode, String signguCode);

    List<MyHomeComplexSource> findAllByHsmpSnIn(Collection<Long> hsmpSns);

    @Query("""
            select distinct source.rnAdres
            from MyHomeComplexSource source
            where source.rnAdres is not null and source.rnAdres <> ''
            """)
    List<String> findDistinctRoadAddresses();
}
