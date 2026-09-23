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
        return result(report, report.failedRequestCount(), report.rateLimitedRequestCount(), 0);
    }

    DataPipelineStepResult adapt(MyHomeComplexCollectionReport report) {
        return result(report, report.failedRequestCount(), report.rateLimitedRequestCount(), 0);
    }

    DataPipelineStepResult adapt(MyHomeComplexMappingReport report) {
        return result(report, report.operationalFailedSourceRowCount(),
                report.rateLimitedSourceRowCount(),
                report.failedSourceRowCount() - report.operationalFailedSourceRowCount());
    }

    DataPipelineStepResult adapt(MyHomeAnnouncementMappingReport report) {
        return result(report, 0, 0, report.failedSourceRowCount());
    }

    DataPipelineStepResult adapt(LhHousingTypeHouseholdEnrichmentReport report) {
        return result(report, 0, 0,
                report.failedSourceComplexCount() + report.unmatchedHousingTypeCount());
    }

    DataPipelineStepResult adapt(LhAnnouncementEnrichmentReport report) {
        return result(report, 0, 0, report.failedSourceCount());
    }

    private <T> DataPipelineStepResult result(
            T report,
            int failureCount,
            int rateLimitedFailureCount,
            int warningCount
    ) {
        try {
            return new DataPipelineStepResult(
                    objectMapper.writeValueAsString(report),
                    failureCount,
                    rateLimitedFailureCount,
                    warningCount
            );
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("파이프라인 단계 응답을 저장할 수 없습니다.", exception);
        }
    }
}
