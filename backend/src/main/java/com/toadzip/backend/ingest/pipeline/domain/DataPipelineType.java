package com.toadzip.backend.ingest.pipeline.domain;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.Arrays;
import java.util.List;

public enum DataPipelineType {
    COMPLEX_COLLECTION("complex-collection"),
    COMPLEX_REFINEMENT("complex-refinement"),
    ANNOUNCEMENT_COLLECTION("announcement-collection"),
    ANNOUNCEMENT_REFINEMENT("announcement-refinement");

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
        return switch (this) {
            case COMPLEX_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_MYHOME_COMPLEXES,
                    DataPipelineStep.COLLECT_LH_LEASE_CATALOG
            );
            case COMPLEX_REFINEMENT -> List.of(
                    DataPipelineStep.MAP_MYHOME_COMPLEXES,
                    DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS
            );
            case ANNOUNCEMENT_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
            );
            case ANNOUNCEMENT_REFINEMENT -> List.of(
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                    DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS
            );
        };
    }

    int sequenceOf(DataPipelineStep step) {
        int index = steps().indexOf(step);
        if (index < 0) {
            throw new IllegalArgumentException("파이프라인 유형에 속하지 않는 단계입니다.");
        }
        return index + 1;
    }
}
