package com.toadzip.backend.announcement.dto.correction;

import com.toadzip.backend.admin.quality.dto.QualityComparison.Source;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.dto.request.RejectUnknownJsonFields;
import com.toadzip.backend.housing.domain.HousingTypeNames;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SupplyCorrection {
    private SupplyCorrection() { }

    /** deletableRowKeys는 지울 수 있는 행(원천 없이 관리자가 추가했고 공통 모집 묶음에 없는 행)이다. 원천행은 제외만 할 수 있다. */
    /** unattachedLhSupplyRows는 LH 원천 소유 공고의 최신 LH 공급 원본 행 중 같은 전용면적 공급행이 없어 붙지 않은 행이다. 오류가 아니라 정보다. */
    /** announcementComplexes는 이 공고의 공고 단지다. 공급행의 complexName이 공고 단지 이름이고, PDF 단지를 주소로 대응할 때 쓴다. */
    /** lhSupplyRows는 소유와 관계없이 이 공고의 최신 LH 공급 원본 행 전체와 각 행이 붙은 공급행이다. 세대수 검산과 식별에만 쓴다. */
    /**
     * applicationTypeNames는 행 키별 주택형(신청형) 이름(공고 표기, 없으면 원천 형명)이다. 행 이름(typeName)은 공고 표기가 없으면 연결
     * 주택형 이름이라 신청형을 가리지 못하므로, 화면은 이 이름으로 서버의 같은 신청형 판정과 같은 기준을 쓴다. 읽기 전용이다.
     * matchingAreas는 행 키별 원천 전용면적(정제가 매칭용으로 적어 둔 공고·LH 면적)이다. 주택형이 없어 행 면적(exclusiveArea)이 비어도
     * 화면이 저장할 곳을 면적으로 고를 수 있게 한다(GH 공급행 틀 '49형' 49.93㎡). 읽기 전용이다.
     */
    public record Workspace(String snapshotToken, long version, List<Row> rows, List<Source> sources, List<String> sourceWarnings,
            List<RowSource> rowSources, List<String> deletableRowKeys, List<LhSupplyRow> unattachedLhSupplyRows,
            List<AnnouncementComplexView> announcementComplexes, List<LhSupplyRow> lhSupplyRows,
            Map<String, String> applicationTypeNames, Map<String, BigDecimal> matchingAreas) { }
    /** linkMethod는 AUTO·ADMIN·MIGRATED, 연결이 없으면(휴지통 단지 포함) null이다. rowCount는 이 공고 단지의 제외하지 않은 공급행 수다. */
    public record AnnouncementComplexView(long id, String name, String roadAddress, Long housingComplexId, String housingComplexName,
            String linkMethod, int rowCount) { }
    public record ComplexLinkRequest(@NotBlank @Size(max = 200) String snapshotToken, @Positive long housingComplexId) { }
    /**
     * LH 공급 원본 한 행. householdCount는 LH 목록의 이번 공급 수(정수가 아니면 null), attachedRowKey는 같은 공고 단지에서 전용면적이
     * 같아 이 LH 행이 붙은 공급행이다(LhRowAttachments). ambiguous는 같은 면적 공급행이 여럿이라 표시 순서 첫 행에 붙였다는 뜻이다.
     */
    public record LhSupplyRow(String rowIdentifier, String complexLabel, String typeName, String exclusiveArea,
            Integer householdCount, String attachedRowKey, boolean ambiguous) { }
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
            @NotNull @Size(max = 500) List<@NotNull @Valid Row> rows,
            @Size(max = 500) List<@NotBlank @Size(max = 64) String> deletedRowKeys) implements RejectUnknownJsonFields {
        public Request {
            if (deletedRowKeys == null) { deletedRowKeys = List.of(); }
        }
        public Request(UUID operationId, String snapshotToken, String reason, String sourceUrl,
                String sourcePage, List<Row> rows) {
            this(operationId, snapshotToken, reason, sourceUrl, sourcePage, rows, List.of());
        }
    }
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
            @Size(max = 50) List<@NotNull @Valid LinkedHousingType> linkedHousingTypes) implements RejectUnknownJsonFields {
        public Row {
            // 화면은 PNU 없는 공급행을 빈 칸으로 보낸다. 저장값(null)과 같은 값으로 받아 바뀐 행으로 보지 않는다.
            if (pnu != null && pnu.isBlank()) { pnu = null; }
        }

        /**
         * 새로 만들 주택형의 단지·이름 키. 한 저장에서 이 키가 같은 행들은 새 주택형 하나를 함께 쓴다. 단지 주택타입은 공고문 표기 이름으로
         * 나누므로 같은 면적이어도 이름이 다르면(55C-1·55D-1) 따로 만든다.
         */
        public String newHousingTypeKey() {
            return newHousingTypeKey(newHousingType);
        }

        public String newHousingTypeKey(NewType type) {
            return housingComplexId + ":" + HousingTypeNames.key(type.name());
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

        public Row withDisplayOrder(int order) {
            return new Row(id, key, housingComplexId, housingComplexName, housingTypeId, newHousingType, groupId,
                    sharedHouseholdCount, protectedValues, excluded, order, complexName, typeName, pnu, moveInMonth,
                    category, householdCount, areaOverride, exclusiveArea, supplyArea, targets, targetCountsComparable,
                    linkedHousingTypes);
        }

        /** 대표 주택형만 바꾼 행. 서버가 전용면적으로 정한 주택형(기존 id 또는 새 주택형)을 넣을 때 쓴다. */
        public Row withHousingType(Long typeId, NewType newType) {
            return new Row(id, key, housingComplexId, housingComplexName, typeId, newType, groupId,
                    sharedHouseholdCount, protectedValues, excluded, displayOrder, complexName, typeName, pnu, moveInMonth,
                    category, householdCount, areaOverride, exclusiveArea, supplyArea, targets, targetCountsComparable,
                    linkedHousingTypes);
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
