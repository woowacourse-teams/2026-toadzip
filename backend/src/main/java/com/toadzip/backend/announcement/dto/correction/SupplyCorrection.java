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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SupplyCorrection {
    private SupplyCorrection() { }

    /** deletableRowKeys는 지울 수 있는 행(원천 없이 관리자가 추가했고 공통 모집 묶음에 없는 행)이다. 원천행은 제외만 할 수 있다. */
    /** unattachedLhSupplyRows는 LH 원천 소유 공고의 최신 LH 공급 원본 행 중 아직 공급행이 되지 않은(LH 행 식별자로 붙지 않은) 행이다. */
    /** announcementComplexes는 이 공고의 공고 단지다. 공급행의 complexName이 공고 단지 이름이고, PDF 단지를 주소로 대응할 때 쓴다. */
    public record Workspace(String snapshotToken, long version, List<Row> rows, List<Source> sources, List<String> sourceWarnings,
            List<RowSource> rowSources, List<String> deletableRowKeys, List<LhSupplyRow> unattachedLhSupplyRows,
            List<AnnouncementComplexView> announcementComplexes) { }
    /** linkMethod는 AUTO·ADMIN·MIGRATED, 연결이 없으면(휴지통 단지 포함) null이다. rowCount는 이 공고 단지의 제외하지 않은 공급행 수다. */
    public record AnnouncementComplexView(long id, String name, String roadAddress, Long housingComplexId, String housingComplexName,
            String linkMethod, int rowCount) { }
    public record ComplexLinkRequest(@NotBlank @Size(max = 200) String snapshotToken, @Positive long housingComplexId) { }
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
            boolean targetCountsComparable,
            @Size(max = 50) List<@NotNull @Valid LinkedHousingType> linkedHousingTypes) {
        public Row {
            // 화면은 PNU 없는 공급행을 빈 칸으로 보낸다. 저장값(null)과 같은 값으로 받아 바뀐 행으로 보지 않는다.
            if (pnu != null && pnu.isBlank()) { pnu = null; }
        }

        /** 새로 만들 주택형의 단지·전용면적 키. 한 저장에서 이 키가 같은 행들은 새 주택형 하나를 함께 쓴다. */
        public String newHousingTypeKey() {
            return newHousingTypeKey(newHousingType);
        }

        public String newHousingTypeKey(NewType type) {
            return housingComplexId + ":" + type.exclusiveArea().stripTrailingZeros().toPlainString();
        }

        /**
         * 함께 모집하는 주택형을 저장 기준으로 맞춘다. 생략한 요청 행은 대표 주택형이 그대로면 저장된 연결을 유지하고
         * 바뀌면 대표만 남긴다. 대표를 바꾸면 연결을 대표 하나로 맞추던 기존 저장과 같게 하려는 것이다.
         * 준 목록은 작업실과 같은 순서로 정렬해, 순서만 다른 요청을 바뀐 행으로 보지 않게 한다.
         */
        public Row withLinksFrom(Row before) {
            if (linkedHousingTypes != null) {
                return withLinkedHousingTypes(linkedHousingTypes.stream().sorted(LinkedHousingType.ORDER).toList());
            }
            boolean sameRepresentative = before != null && newHousingType == null
                    && Objects.equals(before.housingTypeId(), housingTypeId);
            return withLinkedHousingTypes(sameRepresentative ? before.linkedHousingTypes() : List.of());
        }

        public Row withLinkedHousingTypes(List<LinkedHousingType> links) {
            return new Row(id, key, housingComplexId, housingComplexName, housingTypeId, newHousingType, groupId,
                    sharedHouseholdCount, protectedValues, excluded, displayOrder, complexName, typeName, pnu, moveInMonth,
                    category, householdCount, areaOverride, exclusiveArea, supplyArea, targets, targetCountsComparable, links);
        }
    }

    /** 공급행이 대표 주택형과 함께 모집하는 주택형 하나. 기존 주택형 id나 이 저장에서 만들 새 주택형 중 하나만 준다. */
    public record LinkedHousingType(@Positive Long housingTypeId, @Valid NewType newHousingType) {
        /** 작업실 순서. 기존 주택형은 id 순, 새 주택형은 그 뒤에 전용면적 순이다. */
        static final Comparator<LinkedHousingType> ORDER = Comparator.comparing(LinkedHousingType::housingTypeId,
                        Comparator.nullsLast(Comparator.<Long>naturalOrder()))
                .thenComparing(link -> link.newHousingType() == null ? null : link.newHousingType().exclusiveArea(),
                        Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
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
