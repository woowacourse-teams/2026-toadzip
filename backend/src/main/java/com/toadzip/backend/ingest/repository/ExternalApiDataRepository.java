package com.toadzip.backend.ingest.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;

public interface ExternalApiDataRepository extends JpaRepository<ExternalApiData, Long> {

    List<ExternalApiData> findAllByExternalApiOrderByCollectedAtAscIdAsc(ExternalApi externalApi);
}
