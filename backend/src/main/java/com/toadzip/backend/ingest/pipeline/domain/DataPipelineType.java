package com.toadzip.backend.ingest.pipeline.domain;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException;
import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException.Retirement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public enum DataPipelineType {
    COMPLEX_COLLECTION("complex-collection", Domain.COMPLEX, true, false, null),
    COMPLEX_REFINEMENT("complex-refinement", Domain.COMPLEX, false, false, Retirement.COMPLEX_REFINEMENT),
    ANNOUNCEMENT_COLLECTION("announcement-collection", Domain.ANNOUNCEMENT, true, false, null),
    ANNOUNCEMENT_REFINEMENT("announcement-refinement", Domain.ANNOUNCEMENT, false, false, null),
    COMPLEX_SYNC("complex-sync", Domain.COMPLEX, true, true, Retirement.COMPLEX_REFINEMENT),
    ANNOUNCEMENT_SYNC("announcement-sync", Domain.ANNOUNCEMENT, true, true, null),
    ANNOUNCEMENT_REGISTRATION(
            "announcement-registration", Domain.ANNOUNCEMENT, false, true, Retirement.MYHOME_ANNOUNCEMENT_REFINEMENT
    ),
    SH_ANNOUNCEMENT_COLLECTION("sh-announcement-collection", Domain.SH_ANNOUNCEMENT, false, true, null),
    GH_ANNOUNCEMENT_COLLECTION("gh-announcement-collection", Domain.GH_ANNOUNCEMENT, false, true, null);

    private final String pathValue;
    private final Domain domain;
    private final boolean requiresServiceKey;
    private final boolean chainsCollectionToRefinement;
    private final Retirement retirement;

    /**
     * @param requiresServiceKey 공공데이터포털 API를 호출해 서버의 수집 키가 있어야 시작할 수 있다.
     * @param chainsCollectionToRefinement 한 실행에서 수집 뒤 정제까지 이어 가서, 정제 단계가 앞선 수집의 성공을 요구한다.
     * @param retirement 지원을 종료한 유형의 안내 종류. 실행할 수 있는 유형은 null이다.
     *                   지난 실행 이력을 읽도록 종료한 유형과 단계 정의도 남긴다.
     */
    DataPipelineType(
            String pathValue,
            Domain domain,
            boolean requiresServiceKey,
            boolean chainsCollectionToRefinement,
            Retirement retirement
    ) {
        this.pathValue = pathValue;
        this.domain = domain;
        this.requiresServiceKey = requiresServiceKey;
        this.chainsCollectionToRefinement = chainsCollectionToRefinement;
        this.retirement = retirement;
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
            case SH_ANNOUNCEMENT_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_SH_ANNOUNCEMENTS, DataPipelineStep.MAP_SH_ANNOUNCEMENTS
            );
            case GH_ANNOUNCEMENT_COLLECTION -> List.of(
                    DataPipelineStep.COLLECT_GH_ANNOUNCEMENTS, DataPipelineStep.MAP_GH_ANNOUNCEMENTS
            );
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
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                    DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
            );
            case ANNOUNCEMENT_REFINEMENT -> List.of(DataPipelineStep.MAP_LH_ANNOUNCEMENTS);
        };
    }

    public static List<DataPipelineType> ofDomain(Domain domain) {
        return Arrays.stream(values())
                .filter(type -> type.domain == domain)
                .toList();
    }

    public void rejectIfRetired() {
        if (retirement != null) {
            throw RetiredIngestOperationException.of(retirement);
        }
    }

    public boolean requiresServiceKey() {
        return requiresServiceKey;
    }

    public boolean requiresSuccessfulCollection(DataPipelineStep step) {
        return chainsCollectionToRefinement && !step.isCollection();
    }

    public int sequenceOf(DataPipelineStep step) {
        int index = steps().indexOf(step);
        if (index < 0) {
            throw new IllegalArgumentException("파이프라인 유형에 속하지 않는 단계입니다.");
        }
        return index + 1;
    }

    /** 실행 이력을 나눠 보는 구분. SH·GH 공고 수집은 단지·공고 어느 이력에도 넣지 않는다. */
    public enum Domain {
        COMPLEX,
        ANNOUNCEMENT,
        SH_ANNOUNCEMENT,
        GH_ANNOUNCEMENT
    }
}
