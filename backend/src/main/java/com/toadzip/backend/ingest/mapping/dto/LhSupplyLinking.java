package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class LhSupplyLinking {
    private LhSupplyLinking() {
    }

    /** token은 조회 시점의 원천과 저장 연결을 나타낸다. 저장·해제 요청은 이 값으로 동시 변경을 막는다. */
    public record View(String token, List<MyHomeRow> myhomeRows, List<LhRow> lhRows) {
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
