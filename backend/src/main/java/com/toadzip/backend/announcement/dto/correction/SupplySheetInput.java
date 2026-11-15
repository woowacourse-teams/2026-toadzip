package com.toadzip.backend.announcement.dto.correction;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.announcement.dto.request.RejectUnknownJsonFields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 관리자가 저장할 공고 공급표 전체. 모양은 {@link SupplySheetView}와 같고, 요청에 없는 개체는 지운다.
 *
 * <ul>
 *   <li>key는 목록 안에서 겹치지 않는 아무 문자열이다. 저장된 개체는 보기의 key를, 새 개체는 "new:" 접두어 key를 쓴다.</li>
 *   <li>id는 생략할 수 있다. 없으면 주택형은 (공고 단지 또는 단지명, 신청형 이름), 공급대상은 (표기, 신청 조건)으로 저장된 개체를 찾는다.</li>
 *   <li>관리자 수정 표시(adminModified)는 받지 않고 서버가 정한다. 수량 묶음과 임대조건은 key·id 없이 종류와 멤버 칸으로 맞춘다.</li>
 * </ul>
 */
public record SupplySheetInput(
        @NotNull @Size(max = 500) List<@NotNull @Valid HousingType> housingTypes,
        @NotNull @Size(max = 1000) List<@NotNull @Valid Target> targets,
        @NotNull @Size(max = 5000) List<@NotNull @Valid Cell> cells,
        @NotNull @Size(max = 5000) List<@NotNull @Valid Quota> quotas,
        @NotNull @Size(max = 5000) List<@NotNull @Valid Lease> leaseTerms) implements RejectUnknownJsonFields {

    /** 연결한 단지에 이 저장에서 새로 만들 주택형. 기존 주택형(housingTypeId)과 함께 두지 않는다. */
    public record NewHousingType(@NotBlank @Size(max = 255) String name,
            @NotNull @Positive @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea) implements RejectUnknownJsonFields {
    }

    public record HousingType(
            @NotBlank @Size(max = 64) String key,
            @Positive Long id,
            @Positive Long announcementComplexId,
            @NotBlank @Size(max = 255) String complexName,
            @Positive Long housingComplexId,
            @Positive Long housingTypeId,
            @Valid NewHousingType newHousingType,
            @NotBlank @Size(max = 255) String name,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
            YearMonth expectedMoveInMonth,
            @Size(max = 255) String supplyPnu,
            SupplyCategory category,
            @PositiveOrZero int displayOrder) implements RejectUnknownJsonFields {
    }

    /** groups는 공급대상 분류다. 빠지면(null) 저장된 같은 공급대상의 분류를 그대로 두고, 빈 목록이어야 분류를 지운다. */
    public record Target(
            @NotBlank @Size(max = 64) String key,
            @Positive Long id,
            @NotBlank @Size(max = 255) String label,
            @Size(max = 255) String applicationCondition,
            @Size(max = 13) List<@NotNull SupplyTargetGroup> groups,
            @PositiveOrZero int displayOrder) implements RejectUnknownJsonFields {

        public Target {
            groups = groups == null ? null : List.copyOf(groups);
        }
    }

    public record Cell(
            @NotBlank @Size(max = 64) String key,
            @Positive Long id,
            @NotBlank @Size(max = 64) String housingType,
            @NotBlank @Size(max = 64) String target,
            @PositiveOrZero int displayOrder) implements RejectUnknownJsonFields {
    }

    public record Quota(
            @NotNull SupplyQuota.Kind kind,
            @PositiveOrZero int count,
            @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 64) String> members) implements RejectUnknownJsonFields {
    }

    /** 금액은 원 단위 정수다. 하나 이상 있어야 한다. */
    public record Lease(
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rentalDeposit,
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyRent,
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal convertedDeposit,
            @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 64) String> members) implements RejectUnknownJsonFields {
    }
}
