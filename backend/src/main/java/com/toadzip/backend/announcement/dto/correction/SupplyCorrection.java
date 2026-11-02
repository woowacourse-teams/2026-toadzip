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

    /** deletableRowKeys는 지울 수 있는 행(원천 없이 관리자가 추가했고 공통 모집 묶음에 없는 행)이다. 원천행은 제외만 할 수 있다. */
    /** unattachedLhSupplyRows는 LH 원천 소유 공고의 최신 LH 공급 원본 행 중 아직 공급행이 되지 않은(LH 행 식별자로 붙지 않은) 행이다. */
    /** announcementComplexes는 이 공고의 공고 단지다. 공급행의 complexName이 공고 단지 이름이고, PDF 단지를 주소로 대응할 때 쓴다. */
    public record Workspace(String snapshotToken, long version, List<Row> rows, List<Source> sources, List<String> sourceWarnings,
            List<RowSource> rowSources, List<String> deletableRowKeys, List<LhSupplyRow> unattachedLhSupplyRows,
            List<AnnouncementComplexView> announcementComplexes) { }
    public record AnnouncementComplexView(String name, String roadAddress) { }
    public record LhSupplyRow(String rowIdentifier, String complexLabel, String typeName, String exclusiveArea) { }
    /** 공급행이 기록한 원천행. 작업실의 sources에서 같은 분류·키의 원천을 찾아 행 옆에 보여준다. */
    public record RowSource(String rowKey, String category, String sourceKey) { }
    public record Preview(String confirmationToken, List<Row> before, List<Row> after, List<String> warnings,
            List<NewGroup> newGroups) {
        public Preview(String confirmationToken, List<Row> before, List<Row> after, List<String> warnings) {
            this(confirmationToken, before, after, warnings, List.of());
        }
    }
    public record Applied(long changeId, boolean alreadyApplied, Workspace workspace) { }
    public record Request(
            @NotNull UUID operationId,
            @NotBlank @Size(max = 64) String snapshotToken,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
            @Size(max = 100) String sourcePage,
            @NotNull @Size(max = 500) List<@NotNull @Valid Row> rows,
            @Size(max = 250) List<@NotNull @Valid NewGroup> newGroups,
            @Size(max = 500) List<@NotBlank @Size(max = 64) String> deletedRowKeys) {
        public Request {
            if (newGroups == null) { newGroups = List.of(); }
            if (deletedRowKeys == null) { deletedRowKeys = List.of(); }
        }
        public Request(UUID operationId, String snapshotToken, String reason, String sourceUrl,
                String sourcePage, List<Row> rows) {
            this(operationId, snapshotToken, reason, sourceUrl, sourcePage, rows, List.of(), List.of());
        }
    }
    public record NewGroup(@Positive long sourceRowId,
            @NotNull @Size(min = 2, max = 50) List<@NotBlank @Size(max = 64) String> rowKeys,
            @NotNull @PositiveOrZero Integer sharedHouseholdCount) { }
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
            @Size(max = 255) String pnu,
            YearMonth moveInMonth,
            @NotNull SupplyCategory category,
            @PositiveOrZero Integer householdCount,
            boolean areaOverride,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
            @NotNull @Size(max = 200) List<@NotNull @Valid Target> targets,
            boolean targetCountsComparable) {
        public Row {
            // 화면은 PNU 없는 공급행을 빈 칸으로 보낸다. 저장값(null)과 같은 값으로 받아 바뀐 행으로 보지 않는다.
            if (pnu != null && pnu.isBlank()) { pnu = null; }
        }

        /** 새로 만들 주택형의 단지·전용면적 키. 한 저장에서 이 키가 같은 행들은 새 주택형 하나를 함께 쓴다. */
        public String newHousingTypeKey() {
            return housingComplexId + ":" + newHousingType.exclusiveArea().stripTrailingZeros().toPlainString();
        }
    }

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
