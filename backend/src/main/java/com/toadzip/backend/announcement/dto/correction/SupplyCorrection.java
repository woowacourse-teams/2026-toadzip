package com.toadzip.backend.announcement.dto.correction;

import com.toadzip.backend.admin.quality.dto.QualityComparison.Source;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public final class SupplyCorrection {
    private SupplyCorrection() { }

    public record Workspace(String snapshotToken, long version, List<Row> rows, List<Source> sources, List<String> sourceWarnings,
            List<RowSource> rowSources) { }
    /** 공급행이 기록한 원천행. 작업실의 sources에서 같은 분류·키의 원천을 찾아 행 옆에 보여준다. */
    public record RowSource(String rowKey, String category, String sourceKey) { }
    public record Preview(String confirmationToken, List<Row> before, List<Row> after, List<String> warnings) { }
    public record Applied(long changeId, boolean alreadyApplied, Workspace workspace) { }
    public record Request(
            @NotNull UUID operationId,
            @NotBlank @Size(max = 64) String snapshotToken,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
            @Size(max = 100) String sourcePage,
            @NotNull @Size(max = 500) List<@NotNull @Valid Row> rows) { }
    public record Apply(@NotNull @Valid Request correction,
            @NotBlank @Size(max = 64) String confirmationToken) { }

    public record NewType(@NotBlank @Size(max = 255) String name,
            @NotNull @Positive @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea) { }

    public record Row(
            @Positive Long id,
            @NotBlank @Size(max = 64) String key,
            @Positive Long housingComplexId,
            @Size(max = 255) String housingComplexName,
            @Positive Long housingTypeId,
            @Valid NewType newHousingType,
            Long groupId,
            @PositiveOrZero Integer sharedHouseholdCount,
            boolean protectedValues,
            boolean excluded,
            @PositiveOrZero int displayOrder,
            @NotBlank @Size(max = 255) String complexName,
            @NotBlank @Size(max = 255) String typeName,
            @NotBlank @Size(max = 255) String pnu,
            YearMonth moveInMonth,
            @NotNull SupplyCategory category,
            @PositiveOrZero Integer householdCount,
            boolean areaOverride,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
            @NotNull @Size(max = 200) List<@NotNull @Valid Target> targets,
            boolean targetCountsComparable) { }

    public record Target(
            @Positive Long id,
            @NotBlank @Size(max = 64) String key,
            boolean excluded,
            boolean protectedValues,
            @PositiveOrZero int displayOrder,
            @NotBlank @Size(max = 255) String target,
            @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String supplyRank,
            @PositiveOrZero Integer householdCount,
            @PositiveOrZero Integer reserveCount,
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deposit,
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rent,
            @PositiveOrZero @Digits(integer = 18, fraction = 0) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal convertedDeposit,
            @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String condition) { }
}
