package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;

public record AdminSupplyRowUpdateRequest(@NotNull @PositiveOrZero Long version,
        @Positive Long housingComplexId, @Positive Long housingTypeId,
        @NotNull @Valid SupplyData supplyRow,
        @Valid AdminDocumentEvidence documentEvidence) {
    /** 원천 단지명·주택형명은 정제만 바꾼다. 주택형 표기는 이 공고의 표시명으로만 받는다. */
    public record SupplyData(@Size(max = 255) String announcementHousingTypeName,
            @NotBlank @Size(max = 255) String supplyPnu,
            YearMonth expectedMoveInMonth,
            @NotNull SupplyCategory supplyCategory,
            @PositiveOrZero Integer totalSupplyHouseholdCount) { }
}
