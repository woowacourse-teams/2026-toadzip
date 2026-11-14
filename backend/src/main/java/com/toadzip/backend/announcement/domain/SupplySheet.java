package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * 공고 공급표(공급행·칸·수량 묶음·임대조건)를 읽는 쪽이 같은 규칙으로 수를 세게 하는 읽기 모델이다.
 *
 * <ul>
 *   <li>공급행 모집수는 멤버가 모두 그 행의 칸인 묶음의 합이다.</li>
 *   <li>칸 하나만 멤버인 묶음은 그 대상만의 수, 한 행 안의 칸 여럿(행의 모든 칸은 아님)이 멤버인 묶음은 대상끼리 함께 쓰는 수다.</li>
 *   <li>여러 공급행에 걸친 묶음은 행 모집수에 넣지 않고 함께 모집하는 수로 따로 보인다.</li>
 *   <li>공고 모집수 합계는 보이는 행의 칸을 멤버로 가진 묶음을 한 번씩 더한다.</li>
 * </ul>
 */
public final class SupplySheet {

    private static final Comparator<SupplyRowTarget> CELL_ORDER = Comparator
            .comparingInt(SupplyRowTarget::getDisplayOrder)
            .thenComparing(cell -> cell.getTarget().getDisplayOrder())
            .thenComparing(SupplyRowTarget::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final Map<Long, List<SupplyRowTarget>> cellsByRow;
    private final List<SupplyQuota> quotas;
    private final Map<Long, List<SupplyQuota>> quotasByCell = new HashMap<>();
    private final Map<Long, LeaseTerms> leaseByCell = new HashMap<>();

    private SupplySheet(Collection<SupplyRowTarget> cells, Collection<SupplyQuota> quotas,
            Collection<LeaseTerms> leases) {
        this.cellsByRow = cells.stream()
                .sorted(CELL_ORDER)
                .collect(Collectors.groupingBy(cell -> cell.getSupplyRow().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        this.quotas = quotas.stream().sorted(Comparator.comparing(SupplyQuota::getId,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        for (SupplyQuota quota : this.quotas) {
            for (SupplyRowTarget member : quota.getMembers()) {
                quotasByCell.computeIfAbsent(member.getId(), ignored -> new ArrayList<>()).add(quota);
            }
        }
        for (LeaseTerms lease : leases) {
            for (SupplyRowTarget member : lease.getMembers()) {
                leaseByCell.put(member.getId(), lease);
            }
        }
    }

    public static SupplySheet of(Collection<SupplyRowTarget> cells, Collection<SupplyQuota> quotas,
            Collection<LeaseTerms> leases) {
        return new SupplySheet(cells, quotas, leases);
    }

    public static SupplySheet empty() {
        return new SupplySheet(List.of(), List.of(), List.of());
    }

    /** 공급행의 칸(대상)을 공고문 순서로. */
    public List<SupplyRowTarget> cells(long rowId) {
        return cellsByRow.getOrDefault(rowId, List.of());
    }

    /** 멤버가 모두 이 행의 칸인 묶음의 합. 그런 묶음이 없으면 null이다. */
    public Integer rowCount(long rowId, Kind kind) {
        Integer total = null;
        for (SupplyQuota quota : quotas) {
            if (quota.getKind() == kind && rowsOf(quota).equals(Set.of(rowId))) {
                total = (total == null ? 0 : total) + quota.getHouseholdCount();
            }
        }
        return total;
    }

    /** 이 행이 다른 행과 함께 쓰는 모집수 묶음(병합된 수량 칸). */
    public Optional<SupplyQuota> sharedAcrossRows(long rowId, Kind kind) {
        return quotas.stream()
                .filter(quota -> quota.getKind() == kind)
                .filter(quota -> {
                    Set<Long> rows = rowsOf(quota);
                    return rows.size() > 1 && rows.contains(rowId);
                })
                .findFirst();
    }

    /** 이 칸만 멤버인 묶음의 수(그 대상만의 수). 없으면 null이다. */
    public Integer ownCount(SupplyRowTarget cell, Kind kind) {
        Integer total = null;
        for (SupplyQuota quota : quotasOf(cell, kind)) {
            if (quota.getMembers().size() == 1) {
                total = (total == null ? 0 : total) + quota.getHouseholdCount();
            }
        }
        return total;
    }

    /**
     * 한 행 안에서 이 칸이 다른 칸과 함께 쓰는 수. 행의 모든 칸이 함께 쓰는 모집수는 행 모집수로 보이므로 여기에 넣지 않는다.
     * 예비자 수는 행 단위로 보이는 곳이 없으므로 모든 칸이 함께 써도 넣는다.
     */
    public Optional<Shared> sharedInRow(SupplyRowTarget cell, Kind kind) {
        long rowId = cell.getSupplyRow().getId();
        int limit = kind == Kind.WINNER ? cells(rowId).size() : Integer.MAX_VALUE;
        return quotasOf(cell, kind).stream()
                .filter(quota -> quota.getMembers().size() > 1 && quota.getMembers().size() < limit)
                .filter(quota -> rowsOf(quota).equals(Set.of(rowId)))
                .findFirst()
                .map(quota -> new Shared(quota.getId(), quota.getHouseholdCount(), quota.getMembers().stream()
                        .sorted(CELL_ORDER).toList()));
    }

    public Optional<LeaseTerms> lease(SupplyRowTarget cell) {
        return Optional.ofNullable(leaseByCell.get(cell.getId()));
    }

    /** 보이는 행의 칸을 하나라도 멤버로 가진 묶음을 한 번씩 더한다. 그런 묶음이 없으면 null이다. */
    public Integer total(Kind kind, LongPredicate visibleRow) {
        Integer total = null;
        for (SupplyQuota quota : quotas) {
            if (quota.getKind() == kind && rowsOf(quota).stream().anyMatch(visibleRow::test)) {
                total = (total == null ? 0 : total) + quota.getHouseholdCount();
            }
        }
        return total;
    }

    private List<SupplyQuota> quotasOf(SupplyRowTarget cell, Kind kind) {
        return quotasByCell.getOrDefault(cell.getId(), List.of()).stream()
                .filter(quota -> quota.getKind() == kind)
                .toList();
    }

    private static Set<Long> rowsOf(SupplyQuota quota) {
        return quota.getMembers().stream().map(member -> member.getSupplyRow().getId()).collect(Collectors.toSet());
    }

    /** 칸 여럿이 함께 쓰는 수와 그 칸들. */
    public record Shared(Long quotaId, int count, List<SupplyRowTarget> members) {
    }
}
