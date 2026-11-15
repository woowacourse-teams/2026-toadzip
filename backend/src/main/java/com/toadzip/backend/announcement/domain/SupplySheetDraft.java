package com.toadzip.backend.announcement.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공고 하나의 공급표를 통째로 쓸 값이다(SupplySheetStore.replace). 주택형·공급대상·칸은 이 값 안에서만 쓰는 키로 서로를 가리키고,
 * 수량 묶음과 임대조건은 칸 키를 멤버로 가진다. 키가 어긋나거나 겹치는 값은 만들지 않는다.
 *
 * <p>어느 쓰기 경로(정제, 등록, 보정)에서 왔는지는 담지 않는다. 덮어써도 되는지는 부르는 쪽이 공고 소유로 먼저 판단한다.
 * 관리자 수정 표시(adminModified)는 판단 없이 그대로 저장하는 값이다.
 */
public final class SupplySheetDraft {

    private final List<HousingTypeRow> housingTypes;
    private final List<Target> targets;
    private final List<Cell> cells;
    private final List<Quota> quotas;
    private final List<Lease> leases;

    private SupplySheetDraft(List<HousingTypeRow> housingTypes, List<Target> targets, List<Cell> cells,
            List<Quota> quotas, List<Lease> leases) {
        this.housingTypes = List.copyOf(housingTypes);
        this.targets = List.copyOf(targets);
        this.cells = List.copyOf(cells);
        this.quotas = List.copyOf(quotas);
        this.leases = List.copyOf(leases);
    }

    public static SupplySheetDraft of(List<HousingTypeRow> housingTypes, List<Target> targets, List<Cell> cells,
            List<Quota> quotas, List<Lease> leases) {
        SupplySheetDraft draft = new SupplySheetDraft(housingTypes, targets, cells, quotas, leases);
        draft.validateReferences();
        return draft;
    }

    public List<HousingTypeRow> housingTypes() {
        return housingTypes;
    }

    public List<Target> targets() {
        return targets;
    }

    public List<Cell> cells() {
        return cells;
    }

    public List<Quota> quotas() {
        return quotas;
    }

    public List<Lease> leases() {
        return leases;
    }

    private void validateReferences() {
        Set<String> typeKeys = uniqueKeys(housingTypes.stream().map(HousingTypeRow::key).toList(), "주택형");
        Set<String> targetKeys = uniqueKeys(targets.stream().map(Target::key).toList(), "공급대상");
        Set<String> cellKeys = uniqueKeys(cells.stream().map(Cell::key).toList(), "칸");
        uniqueKeys(housingTypes.stream().filter(type -> type.supplyRowId() != null)
                .map(type -> type.supplyRowId().toString()).toList(), "주택형 공급행 ID");
        uniqueKeys(targets.stream().map(target -> AnnouncementSupplyTarget.key(target.label(),
                target.applicationCondition())).toList(), "공급대상 (표기, 신청 조건)");
        uniqueKeys(cells.stream().map(cell -> cell.housingTypeKey() + "\u0000" + cell.targetKey()).toList(),
                "칸 (주택형, 공급대상)");
        Set<String> usedTargets = new HashSet<>();
        for (Cell cell : cells) {
            known(typeKeys, cell.housingTypeKey(), "칸의 주택형");
            known(targetKeys, cell.targetKey(), "칸의 공급대상");
            usedTargets.add(cell.targetKey());
        }
        for (String target : targetKeys) {
            if (!usedTargets.contains(target)) {
                throw new IllegalArgumentException("칸이 없는 공급대상은 둘 수 없다: " + target);
            }
        }
        quotas.forEach(quota -> quota.cellKeys().forEach(key -> known(cellKeys, key, "수량 묶음의 칸")));
        validateLeaseMembers(cellKeys);
    }

    private void validateLeaseMembers(Set<String> cellKeys) {
        Map<String, Integer> leaseOfCell = new HashMap<>();
        for (int index = 0; index < leases.size(); index++) {
            for (String key : leases.get(index).cellKeys()) {
                known(cellKeys, key, "임대조건의 칸");
                if (leaseOfCell.putIfAbsent(key, index) != null) {
                    throw new IllegalArgumentException("칸 하나는 임대조건 하나만 가진다: " + key);
                }
            }
        }
    }

    private static Set<String> uniqueKeys(List<String> keys, String name) {
        Set<String> unique = new HashSet<>();
        for (String key : keys) {
            if (!unique.add(key)) {
                throw new IllegalArgumentException(name + "이 겹친다: " + key);
            }
        }
        return unique;
    }

    private static void known(Set<String> keys, String key, String name) {
        if (!keys.contains(key)) {
            throw new IllegalArgumentException(name + " 키를 찾을 수 없다: " + key);
        }
    }

    private static String requiredKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 키는 필수다.");
        }
        return value;
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "은 필수다.");
        }
        String stripped = value.strip();
        if (stripped.length() > 255) {
            throw new IllegalArgumentException(name + "은 255자 이하여야 한다.");
        }
        return stripped;
    }

    private static String optionalText(String value, String name) {
        if (value == null) {
            return null;
        }
        return text(value, name);
    }

    private static int order(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("표시 순서는 음수일 수 없다.");
        }
        return value;
    }

    private static BigDecimal area(BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException("면적은 음수일 수 없다.");
        }
        return value;
    }

    private static List<String> members(List<String> keys, String name) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException(name + "에는 칸이 하나 이상 있어야 한다.");
        }
        List<String> copied = List.copyOf(keys);
        uniqueKeys(copied, name + "의 칸");
        return copied;
    }

    /**
     * 공고 주택형 한 줄(supply_rows). supplyRowId가 있으면 그 공급행을, 없으면 공고 단지(없으면 단지명의 공고 단지)와 신청형 이름이
     * 같은 공급행을 고친다. 면적이 하나라도 있으면 그 값이 행의 면적이고, 둘 다 없으면 연결 주택타입의 면적이다. 단지(housingComplexId)는
     * 고른 주택타입이 그 단지의 것인지 확인하는 데만 쓰고, 행의 단지는 공고 단지의 연결이다.
     */
    public record HousingTypeRow(String key, Long supplyRowId, Long announcementComplexId, Long housingComplexId,
            Long housingTypeId, String complexName, String typeName, BigDecimal exclusiveArea, BigDecimal supplyArea,
            YearMonth expectedMoveInMonth, String supplyPnu, SupplyCategory supplyCategory, int displayOrder) {

        public HousingTypeRow {
            key = requiredKey(key, "주택형");
            complexName = text(complexName, "단지명");
            typeName = text(typeName, "신청형 이름");
            exclusiveArea = area(exclusiveArea);
            supplyArea = area(supplyArea);
            supplyPnu = optionalText(supplyPnu, "공급 PNU");
            if (supplyCategory == null) {
                throw new IllegalArgumentException("공급구분은 필수다.");
            }
            if (housingTypeId != null && housingComplexId == null) {
                throw new IllegalArgumentException("주택형을 연결하려면 단지도 연결해야 한다.");
            }
            displayOrder = order(displayOrder);
        }

        public boolean overridesArea() {
            return exclusiveArea != null || supplyArea != null;
        }
    }

    /** 공급대상 하나. 신청 조건이 없으면 빈 문자열이다. */
    public record Target(String key, String label, String applicationCondition, int displayOrder,
            boolean adminModified) {

        public Target {
            key = requiredKey(key, "공급대상");
            label = text(label, "공급대상 표기");
            applicationCondition = condition(applicationCondition);
            displayOrder = order(displayOrder);
        }

        private static String condition(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return text(value, "신청 조건");
        }
    }

    /** 공급표 칸: 이 주택형에 이 공급대상이 있다. */
    public record Cell(String key, String housingTypeKey, String targetKey, int displayOrder) {

        public Cell {
            key = requiredKey(key, "칸");
            housingTypeKey = requiredKey(housingTypeKey, "칸의 주택형");
            targetKey = requiredKey(targetKey, "칸의 공급대상");
            displayOrder = order(displayOrder);
        }
    }

    /** 수량 묶음: 수량 칸 하나와 그 칸이 걸친 공급표 칸들. */
    public record Quota(SupplyQuota.Kind kind, int count, List<String> cellKeys, boolean adminModified) {

        public Quota {
            if (kind == null) {
                throw new IllegalArgumentException("수량 묶음의 종류는 필수다.");
            }
            if (count < 0) {
                throw new IllegalArgumentException("모집수는 음수일 수 없다.");
            }
            cellKeys = members(cellKeys, "수량 묶음");
        }
    }

    /** 임대조건: 금액 칸 한 세트와 그 칸이 걸친 공급표 칸들. 금액은 하나 이상 있어야 한다. */
    public record Lease(LeaseTerms.Amounts amounts, List<String> cellKeys, boolean adminModified) {

        public Lease {
            if (amounts == null || amounts.empty()) {
                throw new IllegalArgumentException("임대조건에는 금액이 하나 이상 있어야 한다.");
            }
            cellKeys = members(cellKeys, "임대조건");
        }
    }
}
