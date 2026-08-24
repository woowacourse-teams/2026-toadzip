package com.toadzip.backend.ingest.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.LhNoticeProcessingStatus;

public interface ExternalApiDataRepository extends JpaRepository<ExternalApiData, Long> {

    boolean existsByExternalApiAndRequestDescriptionIn(
            ExternalApi externalApi,
            List<String> requestDescriptions
    );

    boolean existsByExternalApiAndRequestDescriptionAndContentHashAndLhNoticeProcessingStatus(
            ExternalApi externalApi,
            String requestDescription,
            String contentHash,
            LhNoticeProcessingStatus processingStatus
    );

    @Query("""
            SELECT apiData
            FROM ExternalApiData apiData
            WHERE apiData.externalApi = :externalApi
              AND (
                  apiData.lhNoticeProcessingStatus = :processingStatus
                  OR apiData.lhNoticeProcessingStatus IS NULL
              )
            ORDER BY apiData.collectedAt ASC, apiData.id ASC
            """)
    List<ExternalApiData> findAllPendingLhNoticeApiData(
            @Param("externalApi") ExternalApi externalApi,
            @Param("processingStatus") LhNoticeProcessingStatus processingStatus
    );
}
