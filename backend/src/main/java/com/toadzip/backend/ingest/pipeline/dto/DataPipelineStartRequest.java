package com.toadzip.backend.ingest.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DataPipelineStartRequest(
        @NotBlank(message = "서비스키를 입력해 주세요.")
        @Size(max = 4096, message = "서비스키는 4096자 이하여야 합니다.") String serviceKey
) {
    @Override
    public String toString() {
        return "DataPipelineStartRequest[serviceKey=REDACTED]";
    }
}
