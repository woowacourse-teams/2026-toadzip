package com.toadzip.backend.ingest.pipeline.domain;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public enum DataPipelineType {
    COMPLEX_COLLECTION("complex-collection"),
    COMPLEX_REFINEMENT("complex-refinement"),
    ANNOUNCEMENT_COLLECTION("announcement-collection"),
    ANNOUNCEMENT_REFINEMENT("announcement-refinement"),
    COMPLEX_SYNC("complex-sync"),
    ANNOUNCEMENT_SYNC("announcement-sync"),
    ANNOUNCEMENT_REGISTRATION("announcement-registration");

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
            case ANNOUNCEMENT_REGISTRATION -> List.of(
                    DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS,
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS
            );
            case COMPLEX_SYNC -> Stream.concat(
                    COMPLEX_COLLECTION.steps().stream(), COMPLEX_REFINEMENT.steps().stream()
            ).toList();
            case ANNOUNCEMENT_SYNC -> Stream.concat(
                    ANNOUNCEMENT_COLLECTION.steps().stream(), ANNOUNCEMENT_REFINEMENT.steps().stream()
            ).toList();
            case COMPLEX_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_MYHOME_COMPLEXES,
                    DataPipelineStep.COLLECT_LH_LEASE_CATALOG
            );
            // 새 단지 주소의 좌표가 필요하면 관리자가 위치정보요약DB ZIP을 별도 업로드한다.
            case COMPLEX_REFINEMENT -> List.of(
                    DataPipelineStep.MAP_MYHOME_COMPLEXES,
                    DataPipelineStep.ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS
            );
            case ANNOUNCEMENT_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
            );
            case ANNOUNCEMENT_REFINEMENT -> List.of(
                    DataPipelineStep.MAP_MYHOME_ANNOUNCEMENTS,
                    DataPipelineStep.ENRICH_LH_ANNOUNCEMENTS
            );
        };
    }

    public boolean requiresSuccessfulCollection(DataPipelineStep step) {
        return (this == COMPLEX_SYNC || this == ANNOUNCEMENT_SYNC || this == ANNOUNCEMENT_REGISTRATION)
                && !step.isCollection();
    }

    public int sequenceOf(DataPipelineStep step) {
        int index = steps().indexOf(step);
        if (index < 0) {
            throw new IllegalArgumentException("파이프라인 유형에 속하지 않는 단계입니다.");
        }
        return index + 1;
    }
}
