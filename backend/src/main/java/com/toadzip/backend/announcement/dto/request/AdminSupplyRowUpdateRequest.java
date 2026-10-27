package com.toadzip.backend.announcement.dto.request;

import jakarta.validation.Valid;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminSupplyRowUpdateRequest(@NotNull @PositiveOrZero Long version,
        @Positive Long housingComplexId, @Positive Long housingTypeId,
        @NotNull @Valid SupplyData supplyRow,
        @Valid AdminDocumentEvidence documentEvidence) {
    /** 원천 단지명·주택형명은 정제만 바꾼다. 주택형 표기는 이 공고의 표시명으로만 받는다. */
    public record SupplyData(@Size(max = 255) String announcementHousingTypeName,
            @NotBlank @Size(max = 255) String supplyPnu,
            java.time.YearMonth expectedMoveInMonth,
            @NotNull com.toadzip.backend.announcement.domain.SupplyCategory supplyCategory,
            @PositiveOrZero Integer totalSupplyHouseholdCount) { }
}
