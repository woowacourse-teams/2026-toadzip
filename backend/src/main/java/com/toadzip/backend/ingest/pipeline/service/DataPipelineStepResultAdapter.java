package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class DataPipelineStepResultAdapter {

    private final ObjectMapper objectMapper;

    DataPipelineStepResultAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DataPipelineStepResult adapt(ExternalDataCollectionReport report) {
        return result(report, report.failedRequestCount(), report.rateLimitedRequestCount());
    }

    DataPipelineStepResult adapt(MyHomeComplexCollectionReport report) {
        return result(report, report.failedRequestCount(), report.rateLimitedRequestCount());
    }

    DataPipelineStepResult adapt(MyHomeComplexMappingReport report) {
        return result(report, report.failedSourceRowCount(), report.rateLimitedSourceRowCount());
    }

    DataPipelineStepResult adapt(MyHomeAnnouncementMappingReport report) {
        return result(report, report.failedSourceRowCount(), 0);
    }

    DataPipelineStepResult adapt(LhHousingTypeHouseholdEnrichmentReport report) {
        return result(report, report.failedSourceComplexCount(), 0);
    }

    DataPipelineStepResult adapt(LhAnnouncementEnrichmentReport report) {
        return result(report, report.failedSourceCount(), 0);
    }

    private <T> DataPipelineStepResult result(
            T report,
            int failureCount,
            int rateLimitedFailureCount
    ) {
        try {
            return new DataPipelineStepResult(
                    objectMapper.writeValueAsString(report),
                    failureCount,
                    rateLimitedFailureCount
            );
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("파이프라인 단계 응답을 저장할 수 없습니다.", exception);
        }
    }
}
