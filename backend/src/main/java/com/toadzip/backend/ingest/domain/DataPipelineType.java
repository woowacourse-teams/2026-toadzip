package com.toadzip.backend.ingest.domain;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.Arrays;
import java.util.List;

public enum DataPipelineType {
    COLLECTION("collection"),
    REFINEMENT("refinement");

    private final String pathValue;

    DataPipelineType(String pathValue) {
        this.pathValue = pathValue;
    }

    public static DataPipelineType fromPathValue(String pathValue) {
        return Arrays.stream(values())
                .filter(type -> type.pathValue.equals(pathValue))
                .findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException(
                        "지원하지 않는 데이터 수집·정제 작업입니다."
                ));
    }

    public List<DataPipelineStep> steps() {
        return Arrays.stream(DataPipelineStep.values())
                .filter(step -> step.belongsTo(this))
                .toList();
    }
}
