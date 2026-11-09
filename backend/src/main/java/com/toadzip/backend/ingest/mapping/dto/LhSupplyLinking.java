package com.toadzip.backend.ingest.mapping.dto;

import java.time.Instant;
import java.util.List;

public final class LhSupplyLinking {
    private LhSupplyLinking() {
    }

    /**
     * token은 조회 시점의 원천과 저장 연결을 나타낸다.
     * failures는 현재 원천과 등록 공급행으로 LH 보강 매칭을 다시 해 본 결과이며 저장된 오류 기록과 시점이 다를 수 있다.
     * releasedSupplyMatches는 연결 저장·해제가 지원을 종료해 늘 비어 있다. 조회 응답 모양을 지키려고 남긴다.
     */
    public record View(String token, Announcement announcement, List<MyHomeRow> myhomeRows, List<LhRow> lhRows,
            List<MatchFailure> failures, List<String> releasedSupplyMatches) {
    }

    public record Announcement(String identifier, String name, String sourceUrl, String panId) {
    }

    public record MatchFailure(String lhRowIdentifier, String reason, String detail, List<Candidate> candidates) {
    }

    public record Candidate(long supplyRowId, String myhomeRowIdentifier, String sourceComplexName,
            String sourceHousingTypeName, String complexName, String housingTypeName, String lhRowIdentifier) {
    }

    public record MyHomeRow(String rowIdentifier, String complexName, String housingTypeName,
            Integer supplyHouseholdCount, boolean splitCorrected) {
    }

    /** linkedRowIdentifier는 지금 정제가 이 LH 행을 붙이는 마이홈 원천행이다. 없으면 연결되지 않은 행이다. */
    public record LhRow(String rowIdentifier, String complexLabel, String typeName, String exclusiveArea,
            String supplyArea, String suppliedUnitCount, String linkedRowIdentifier, SavedLink savedLink) {
    }

    public record SavedLink(String myhomeRowIdentifier, String sourceUrl, String reason, String actor,
            Instant updatedAt, boolean reviewRequired) {
    }
}
