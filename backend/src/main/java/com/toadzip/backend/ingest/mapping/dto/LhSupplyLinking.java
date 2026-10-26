package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class LhSupplyLinking {
    private LhSupplyLinking() {
    }

    /**
     * token은 조회 시점의 원천과 저장 연결을 나타낸다. 저장·해제 요청은 이 값으로 동시 변경을 막는다.
     * failures는 현재 원천과 등록 공급행으로 LH 보강 매칭을 다시 해 본 결과이며 저장된 오류 기록과 시점이 다를 수 있다.
     * releasedSupplyMatches는 이번 저장·해제로 LH 행 구성이 바뀌어 함께 해제한 저장 매칭의 행 식별자다. 조회에서는 비어 있다.
     */
    public record View(String token, Announcement announcement, List<MyHomeRow> myhomeRows, List<LhRow> lhRows,
            List<MatchFailure> failures, List<String> releasedSupplyMatches) {
    }

    /** 공고의 현재 LH 공급 원본 행. PDF 보정이 공급행에 아직 붙지 않은 LH 행을 찾을 때 쓴다. */
    public record CurrentSupply(String rowIdentifier, String complexLabel, String typeName, String exclusiveArea) {
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

    public record SaveRequest(@NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 500) String myhomeRowIdentifier,
            @NotNull @Size(min = 1, max = 200) List<@NotBlank @Size(max = 200) String> lhRowIdentifiers,
            @NotBlank @Size(max = 2048) String sourceUrl, @NotBlank @Size(max = 2000) String reason) {
    }

    public record ReleaseRequest(@NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 200) String lhRowIdentifier, @NotBlank @Size(max = 2000) String reason) {
    }
}
