package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.domain.IngestStepReport;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class DataPipelineStepResultAdapter {

    private final ObjectMapper objectMapper;

    DataPipelineStepResultAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DataPipelineStepResult adapt(IngestStepReport report) {
        try {
            return new DataPipelineStepResult(
                    objectMapper.writeValueAsString(report),
                    report.stepFailureCount(),
                    report.stepRateLimitedFailureCount(),
                    report.stepWarningCount()
            );
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("파이프라인 단계 응답을 저장할 수 없습니다.", exception);
        }
    }
}
