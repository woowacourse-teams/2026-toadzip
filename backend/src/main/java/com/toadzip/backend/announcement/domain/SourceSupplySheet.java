package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.announcement.domain.SupplySheetDraft.Cell;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Lease;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Quota;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 공급행마다 대상 목록과 모집수를 가진 원천·등록 공급(LH·GH 정제, 관리자 등록, JSON 등록)을 공급표 draft로 바꾼다.
 * 옛 대상 테이블을 공급표로 다시 만들던 규칙(V20261113_02 supply_sheet_rebuild)과 같은 공급표가 나온다.
 *
 * <ol>
 *   <li>공급대상: 표기가 빈 대상은 버리고, (표기, 신청 조건)을 공고마다 한 번씩 처음 나온 공급행·대상 순서로 둔다. 분류는 두지 않는다
 *       (표기를 분류로 읽는 규칙은 AI 워커에 있고, 관리자가 공급표 화면에서 정한다).</li>
 *   <li>칸: 대상 하나가 칸 하나다. 한 공급행에 같은 (표기, 조건)이 또 나오면 먼저 나온 칸에 수를 더하고 금액은 먼저 나온 것을 쓴다.
 *       대상이 없는 공급행은 '전체' 칸 하나를 둔다.</li>
 *   <li>수량 묶음: 칸마다 그 대상만의 당첨·예비 수. 공급행 모집수 중 대상별 수로 나뉘지 않은 나머지는 그 행의 모든 칸이 함께 쓴다.
 *       칸이 '전체'가 아닌 대상 하나뿐이면 '전체' 칸을 더해 나머지가 그 대상의 수로 읽히지 않게 한다. 대상별 수의 합이 모집수보다
 *       크면 모집수를 버린다. 모집수 0은 0으로 남긴다.</li>
 *   <li>임대조건: 금액이 하나라도 있는 칸마다 하나. 금액이 같아도 합치지 않는다.</li>
 * </ol>
 *
 * <p>주택형 값(관리자 등록·JSON 등록, 기관 준비 데이터 {@link AgencySupplySheet})은 draft 주택형 값을 그대로 받는다.
 */
public final class SourceSupplySheet {

    private static final int EVERYONE_ORDER = 1000;

    private SourceSupplySheet() {
    }

    public static SupplySheetDraft draft(List<Row> rows) {
        List<Row> sorted = rows.stream().sorted(Comparator.comparingInt((Row row) -> row.housingType().displayOrder())
                .thenComparing(row -> row.housingType().supplyRowId(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Targets targets = new Targets();
        Map<Row, Map<String, CellValues>> cellsByRow = new LinkedHashMap<>();
        for (Row row : sorted) {
            cellsByRow.put(row, cells(row, targets));
        }
        List<Quota> quotas = new ArrayList<>();
        List<Quota> rests = new ArrayList<>();
        List<Lease> leases = new ArrayList<>();
        for (var entry : cellsByRow.entrySet()) {
            Map<String, CellValues> cells = entry.getValue();
            cells.values().forEach(cell -> {
                cell.own(quotas);
                if (!cell.amounts().empty()) {
                    leases.add(new Lease(cell.amounts(), List.of(cell.key()), false));
                }
            });
            rest(entry.getKey(), cells, targets).ifPresent(rests::add);
        }
        quotas.addAll(rests);
        List<Cell> cells = cellsByRow.values().stream().flatMap(row -> row.values().stream())
                .map(cell -> new Cell(cell.key(), cell.housingTypeKey(), cell.targetKey(), cell.order())).toList();
        return SupplySheetDraft.of(sorted.stream().map(Row::housingType).toList(),
                targets.list(), cells, quotas, leases);
    }

    private static Map<String, CellValues> cells(Row row, Targets targets) {
        Map<String, CellValues> cells = new LinkedHashMap<>();
        List<Target> ordered = row.targets().stream().filter(target -> target.label() != null && !target.label().isBlank())
                .sorted(Comparator.comparingInt(Target::displayOrder)).toList();
        for (Target target : ordered) {
            String targetKey = targets.key(target.label(), target.applicationCondition());
            CellValues stored = cells.get(targetKey);
            if (stored == null) {
                cells.put(targetKey, new CellValues(cellKey(row, targetKey), housingTypeKey(row), targetKey,
                        target.displayOrder(), target.winnerCount(), target.reserveCount(), target.amounts()));
            } else {
                cells.put(targetKey, stored.add(target));
            }
        }
        if (cells.isEmpty()) {
            String everyone = targets.everyone();
            cells.put(everyone, CellValues.empty(cellKey(row, everyone), housingTypeKey(row), everyone, 0));
        }
        return cells;
    }

    /** 공급행 모집수 중 대상별 당첨 수로 나뉘지 않은 나머지. 행의 모든 칸이 함께 쓴다. */
    private static Optional<Quota> rest(Row row, Map<String, CellValues> cells, Targets targets) {
        Integer total = row.householdCount();
        if (total == null) {
            return Optional.empty();
        }
        int counted = cells.values().stream().map(CellValues::winner).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        if (total <= counted && !(total == 0 && counted == 0)) {
            return Optional.empty();
        }
        if (cells.size() == 1 && !targets.everyoneLabel(cells.keySet().iterator().next())) {
            String everyone = targets.everyone();
            cells.put(everyone, CellValues.empty(cellKey(row, everyone), housingTypeKey(row), everyone, EVERYONE_ORDER));
        }
        return Optional.of(new Quota(SupplyQuota.Kind.WINNER, total - counted,
                cells.values().stream().map(CellValues::key).toList(), row.adminModified()));
    }

    private static String housingTypeKey(Row row) {
        return row.housingType().key();
    }

    private static String cellKey(Row row, String targetKey) {
        return "cell:" + row.housingType().key() + ":" + targetKey;
    }

    /**
     * 공급행(주택형) 하나와 그 공급: 모집수(모르면 null)와 대상들. 대상이 없으면 '전체' 칸 하나가 모집수를 갖는다. adminModified는 행
     * 모집수 묶음의 관리자 수정 표시다.
     */
    public record Row(SupplySheetDraft.HousingTypeRow housingType, boolean adminModified, Integer householdCount,
            List<Target> targets) {

        public Row {
            if (housingType == null) {
                throw new IllegalArgumentException("주택형이 필요하다.");
            }
            targets = List.copyOf(targets);
        }
    }

    /** 공급행의 대상 하나. 수·금액은 모르면 null이고, 표시 순서는 행 안에서의 순서다. */
    public record Target(String label, String applicationCondition, Integer winnerCount, Integer reserveCount,
            LeaseTerms.Amounts amounts, int displayOrder) {

        public Target {
            if (amounts == null) {
                amounts = new LeaseTerms.Amounts(null, null, null);
            }
        }
    }

    /**
     * 공급대상을 (표기, 신청 조건)으로 한 번씩, 대상으로 처음 나온 순서대로 모은다. '전체'(조건 없음)는 대상에서 오지 않았으면 맨 뒤이고,
     * 앞 행이 칸을 채우려고 먼저 썼어도 뒤 행의 대상으로 나오면 그 순서를 받는다.
     */
    private static final class Targets {

        private final Map<String, SupplySheetDraft.Target> byKey = new LinkedHashMap<>();
        private final Set<String> ranked = new HashSet<>();
        private int next;

        String key(String label, String applicationCondition) {
            String naturalKey = AnnouncementSupplyTarget.key(label, applicationCondition);
            String key = "target:" + naturalKey;
            if (ranked.add(key)) {
                byKey.put(key, new SupplySheetDraft.Target(key, label, applicationCondition, List.of(), next++, false));
            }
            return key;
        }

        String everyone() {
            String key = "target:" + AnnouncementSupplyTarget.key(AnnouncementSupplyTarget.EVERYONE, "");
            byKey.computeIfAbsent(key, ignored -> new SupplySheetDraft.Target(key, AnnouncementSupplyTarget.EVERYONE, "",
                    List.of(), EVERYONE_ORDER, false));
            return key;
        }

        boolean everyoneLabel(String key) {
            return byKey.get(key).label().equals(AnnouncementSupplyTarget.EVERYONE);
        }

        List<SupplySheetDraft.Target> list() {
            return List.copyOf(byKey.values());
        }
    }

    private record CellValues(String key, String housingTypeKey, String targetKey, int order, Integer winner,
            Integer reserve, LeaseTerms.Amounts amounts) {

        static CellValues empty(String key, String housingTypeKey, String targetKey, int order) {
            return new CellValues(key, housingTypeKey, targetKey, order, null, null,
                    new LeaseTerms.Amounts(null, null, null));
        }

        CellValues add(Target target) {
            return new CellValues(key, housingTypeKey, targetKey, order, sum(winner, target.winnerCount()),
                    sum(reserve, target.reserveCount()), amounts);
        }

        void own(List<Quota> quotas) {
            if (winner != null) {
                quotas.add(new Quota(SupplyQuota.Kind.WINNER, winner, List.of(key), false));
            }
            if (reserve != null) {
                quotas.add(new Quota(SupplyQuota.Kind.RESERVE, reserve, List.of(key), false));
            }
        }

        private static Integer sum(Integer left, Integer right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return left + right;
        }
    }
}
