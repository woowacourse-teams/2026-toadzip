package com.toadzip.backend.housing.dto.request;

import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 관리자가 단지에 주택형을 직접 추가한다. 입주 전 신규 단지처럼 수집 원천에 주택형이 없을 때 쓴다. */
public record AdminHousingTypeCreateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 255) String name,
        @NotNull @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
        @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
        @PositiveOrZero Integer householdCount,
        @Valid AdminDocumentEvidence documentEvidence) {
}
