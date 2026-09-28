package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexLinkRepository extends JpaRepository<MyHomeComplexLink, String> {

    List<MyHomeComplexLink> findAllByHousingComplexId(long complexId);

    List<MyHomeComplexLink> findAllByMergeIdIsNotNull();

    List<MyHomeComplexLink> findAllBySourceComplexIdentifierStartingWithAndMergeIdIsNotNull(String prefix);
}
