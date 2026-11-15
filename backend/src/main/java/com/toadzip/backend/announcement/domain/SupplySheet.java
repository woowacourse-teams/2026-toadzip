package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공고 하나의 공급표(공급행·칸·수량 묶음·임대조건)를 읽는 쪽이 같은 규칙으로 수를 세게 하는 읽기 모델이다. 정본(공고 공급표 엔티티,
 * {@link #of})과 기관 준비 데이터를 공급표로 바꾼 것({@link #fromDraft})이 같은 모양이다.
 *
 * <ul>
 *   <li>공급행 모집수는 멤버가 모두 그 행의 칸인 묶음의 합이다.</li>
 *   <li>칸 하나만 멤버인 묶음은 그 대상만의 수, 한 행 안의 칸 여럿(행의 모든 칸은 아님)이 멤버인 묶음은 대상끼리 함께 쓰는 수다.</li>
 *   <li>여러 공급행에 걸친 묶음은 행 모집수에 넣지 않고 함께 모집하는 수로 따로 보인다.</li>
 *   <li>공고 모집수 합계는 보이는 행의 칸을 멤버로 가진 묶음을 한 번씩 더한다.</li>
 * </ul>
 *
 * <p>보이는 행({@link #rows})은 관리자가 제외하지 않은 행이다. 제외한 행의 칸도 묶음 멤버로는 남아 여러 행에 걸친 묶음을 가린다.
 */
public final class SupplySheet {

    private static final Comparator<Cell> CELL_ORDER = Comparator.comparingInt(Cell::displayOrder)
            .thenComparingInt(Cell::targetOrder).thenComparingLong(Cell::id);

    private final List<Row> rows;
    private final Map<Long, Cell> cellsById = new HashMap<>();
    private final Map<Long, List<Cell>> cellsByRow;
    private final List<Quota> quotas;
    private final Map<Long, List<Quota>> quotasByCell = new HashMap<>();
    private final Map<Long, Lease> leaseByCell = new HashMap<>();

    private SupplySheet(List<Row> rows, Collection<Cell> cells, Collection<Quota> quotas, Collection<Lease> leases) {
        this.rows = List.copyOf(rows);
        cells.forEach(cell -> cellsById.put(cell.id(), cell));
        this.cellsByRow = cells.stream().sorted(CELL_ORDER)
                .collect(Collectors.groupingBy(Cell::rowId, LinkedHashMap::new, Collectors.toList()));
        this.quotas = quotas.stream().sorted(Comparator.comparingLong(Quota::id)).toList();
        for (Quota quota : this.quotas) {
            quota.members().forEach(member -> quotasByCell.computeIfAbsent(member, ignored -> new ArrayList<>()).add(quota));
        }
        for (Lease lease : leases) {
            lease.members().forEach(member -> leaseByCell.put(member, lease));
        }
    }

    /**
     * 정본 공급표. 공급행은 공고 하나의 행 전부(제외한 행 포함)이고, 칸·묶음·임대조건도 그 공고의 것이다. 공급행 순서는 표시 순서, ID다.
     */
    public static SupplySheet of(List<SupplyRow> supplyRows, Collection<SupplyRowTarget> cells,
            Collection<SupplyQuota> quotas, Collection<LeaseTerms> leases) {
        List<Row> visible = supplyRows.stream().filter(row -> !row.isAdminExcluded())
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .map(row -> new Row(row.getId(), row.getAnnouncementComplex().getSourceComplexName(),
                        row.getHousingTypeName(), row.getHousingTypeName(),
                        row.getAnnouncementComplex().linkedComplex(), row.getHousingType(), row.getExpectedMoveInMonth(), row.getSupplyCategory(),
                        row.getExclusiveArea(), row.getSupplyArea(), row.isAdminModified()))
                .toList();
        return new SupplySheet(visible,
                cells.stream().map(cell -> new Cell(cell.getId(), cell.getSupplyRow().getId(),
                        cell.getTarget().getLabel(), cell.getTarget().getApplicationCondition(), cell.getDisplayOrder(),
                        cell.getTarget().getDisplayOrder())).toList(),
                quotas.stream().map(quota -> new Quota(quota.getId(), quota.getKind(), quota.getHouseholdCount(),
                        quota.getMembers().stream().map(SupplyRowTarget::getId).collect(Collectors.toSet()))).toList(),
                leases.stream().map(lease -> new Lease(lease.amounts(),
                        lease.getMembers().stream().map(SupplyRowTarget::getId).collect(Collectors.toSet()))).toList());
    }

    /**
     * 저장하지 않은 공급표 draft를 읽기 모델로 바꾼다. 공급행은 rows가 draft 주택형 키별로 준 보이는 값이고, 칸·묶음 ID는 이 공급표 안에서만
     * 쓰는 음수다.
     */
    public static SupplySheet fromDraft(SupplySheetDraft draft, Map<String, Row> rows) {
        Map<String, SupplySheetDraft.Target> targets = draft.targets().stream()
                .collect(Collectors.toMap(SupplySheetDraft.Target::key, Function.identity()));
        Map<String, Long> cellIds = new HashMap<>();
        List<Cell> cells = new ArrayList<>();
        for (SupplySheetDraft.Cell cell : draft.cells()) {
            long id = -(cells.size() + 1L);
            cellIds.put(cell.key(), id);
            SupplySheetDraft.Target target = targets.get(cell.targetKey());
            cells.add(new Cell(id, rows.get(cell.housingTypeKey()).id(), target.label(), target.applicationCondition(),
                    cell.displayOrder(), target.displayOrder()));
        }
        List<Quota> quotas = new ArrayList<>();
        for (SupplySheetDraft.Quota quota : draft.quotas()) {
            quotas.add(new Quota(-(quotas.size() + 1L), quota.kind(), quota.count(),
                    quota.cellKeys().stream().map(cellIds::get).collect(Collectors.toSet())));
        }
        List<Lease> leases = draft.leases().stream().map(lease -> new Lease(lease.amounts(),
                lease.cellKeys().stream().map(cellIds::get).collect(Collectors.toSet()))).toList();
        return new SupplySheet(draft.housingTypes().stream().map(type -> rows.get(type.key())).toList(), cells, quotas,
                leases);
    }

    public static SupplySheet empty() {
        return new SupplySheet(List.of(), List.of(), List.of(), List.of());
    }

    /** 보이는 공급행을 공고문 순서로. */
    public List<Row> rows() {
        return rows;
    }

    /** 공급행의 칸(대상)을 공고문 순서로. */
    public List<Cell> cells(long rowId) {
        return cellsByRow.getOrDefault(rowId, List.of());
    }

    /** 멤버가 모두 이 행의 칸인 묶음의 합. 그런 묶음이 없으면 null이다. */
    public Integer rowCount(long rowId, Kind kind) {
        Integer total = null;
        for (Quota quota : quotas) {
            if (quota.kind() == kind && rowsOf(quota).equals(Set.of(rowId))) {
                total = (total == null ? 0 : total) + quota.count();
            }
        }
        return total;
    }

    /** 이 행이 다른 행과 함께 쓰는 모집수 묶음(병합된 수량 칸). */
    public Optional<Quota> sharedAcrossRows(long rowId, Kind kind) {
        return quotas.stream()
                .filter(quota -> quota.kind() == kind)
                .filter(quota -> {
                    Set<Long> memberRows = rowsOf(quota);
                    return memberRows.size() > 1 && memberRows.contains(rowId);
                })
                .findFirst();
    }

    /** 이 칸만 멤버인 묶음의 수(그 대상만의 수). 없으면 null이다. */
    public Integer ownCount(Cell cell, Kind kind) {
        Integer total = null;
        for (Quota quota : quotasOf(cell, kind)) {
            if (quota.members().size() == 1) {
                total = (total == null ? 0 : total) + quota.count();
            }
        }
        return total;
    }

    /**
     * 한 행 안에서 이 칸이 다른 칸과 함께 쓰는 수. 행의 모든 칸이 함께 쓰는 모집수는 행 모집수로 보이므로 여기에 넣지 않는다.
     * 예비자 수는 행 단위로 보이는 곳이 없으므로 모든 칸이 함께 써도 넣는다.
     */
    public Optional<Shared> sharedInRow(Cell cell, Kind kind) {
        long rowId = cell.rowId();
        int limit = kind == Kind.WINNER ? cells(rowId).size() : Integer.MAX_VALUE;
        return quotasOf(cell, kind).stream()
                .filter(quota -> quota.members().size() > 1 && quota.members().size() < limit)
                .filter(quota -> rowsOf(quota).equals(Set.of(rowId)))
                .findFirst()
                .map(quota -> new Shared(quota.id(), quota.count(), quota.members().stream().map(cellsById::get)
                        .sorted(CELL_ORDER).toList()));
    }

    /**
     * 이 칸이 든 가장 작은 묶음(멤버가 가장 적고, 같으면 먼저 만든 묶음)의 수. 그 칸만의 수가 있으면 그 수이고, 없으면 그 칸이 함께
     * 쓰는 수다. 칸이 든 묶음이 없으면 null이다.
     */
    public Integer smallestCount(Cell cell, Kind kind) {
        return quotasOf(cell, kind).stream().min(Comparator.comparingInt(quota -> quota.members().size()))
                .map(Quota::count).orElse(null);
    }

    public Optional<Lease> lease(Cell cell) {
        return Optional.ofNullable(leaseByCell.get(cell.id()));
    }

    /** 보이는 행의 칸을 하나라도 멤버로 가진 묶음을 한 번씩 더한다. 그런 묶음이 없으면 null이다. */
    public Integer total(Kind kind) {
        Set<Long> visible = rows.stream().map(Row::id).collect(Collectors.toSet());
        Integer total = null;
        for (Quota quota : quotas) {
            if (quota.kind() == kind && rowsOf(quota).stream().anyMatch(visible::contains)) {
                total = (total == null ? 0 : total) + quota.count();
            }
        }
        return total;
    }

    private List<Quota> quotasOf(Cell cell, Kind kind) {
        return quotasByCell.getOrDefault(cell.id(), List.of()).stream()
                .filter(quota -> quota.kind() == kind)
                .toList();
    }

    private Set<Long> rowsOf(Quota quota) {
        return quota.members().stream().map(member -> cellsById.get(member).rowId()).collect(Collectors.toSet());
    }

    /**
     * 보이는 공급행 하나. 이름·면적은 화면에 보일 값이다. 정본은 공고 단지 이름과 행의 신청형 이름·면적이고, 준비 데이터는 원천 형명과
     * 임시로 고른 주택타입 값이다({@link AgencySupplySheet}). 원천 단지명·형명은 공개 응답의 원문 칸이다. 단지는 공고 단지가 이은
     * 단지다(공고↔단지 연결은 공고 단지로 정한다).
     */
    public record Row(long id, String sourceComplexName, String sourceHousingTypeName, String displayTypeName,
            HousingComplex complex, HousingType housingType, YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory, BigDecimal exclusiveArea, BigDecimal supplyArea, boolean adminModified) {
    }

    /** 공급표 칸: 이 공급행의 이 공급대상. */
    public record Cell(long id, long rowId, String label, String applicationCondition, int displayOrder,
            int targetOrder) {

        public boolean everyone() {
            return AnnouncementSupplyTarget.EVERYONE.equals(label);
        }
    }

    /** 수량 묶음: 수와 그 수를 함께 쓰는 칸 ID들. */
    public record Quota(long id, Kind kind, int count, Set<Long> members) {

        public Quota {
            members = Set.copyOf(members);
        }
    }

    /** 임대조건: 금액 한 세트와 그 금액을 쓰는 칸 ID들. */
    public record Lease(LeaseTerms.Amounts amounts, Set<Long> members) {

        public Lease {
            members = Set.copyOf(members);
        }
    }

    /** 칸 여럿이 함께 쓰는 수와 그 칸들. */
    public record Shared(long quotaId, int count, List<Cell> members) {
    }
}
