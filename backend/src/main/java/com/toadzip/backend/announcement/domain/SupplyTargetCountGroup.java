package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 한 공급행 안에서 공급대상 여럿이 함께 쓰는 모집수(공통 모집수) 하나. 공고문이 대상끼리 한 번만 적은 수를 대상마다 나누거나
 * 같은 수를 중복 저장하지 않고 여기에 한 번만 둔다. 종류(공급세대수·예비자수)마다 따로 묶고, 묶인 대상은 그 종류의 칸을 비워 둔다.
 */
@Getter
@Entity
@Table(name = "supply_target_count_groups")
@NoArgsConstructor(access = PROTECTED)
public class SupplyTargetCountGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "supply_row_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SupplyRow supplyRow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false)
    private int householdCount;

    private SupplyTargetCountGroup(SupplyRow supplyRow, Kind kind, int householdCount) {
        if (supplyRow == null || kind == null) {
            throw new IllegalArgumentException("공통 모집수의 공급행과 종류는 필수다.");
        }
        if (householdCount < 0) {
            throw new IllegalArgumentException("공통 모집수는 음수일 수 없다.");
        }
        this.supplyRow = supplyRow;
        this.kind = kind;
        this.householdCount = householdCount;
    }

    public static SupplyTargetCountGroup create(SupplyRow supplyRow, Kind kind, int householdCount) {
        return new SupplyTargetCountGroup(supplyRow, kind, householdCount);
    }

    /**
     * 제외하지 않은 대상의 공급세대수 합. 대상끼리 함께 쓰는 공급세대수는 묶음마다 한 번만 더한다(행 모집수와 대조할 때 쓴다).
     * PDF 보정 미리보기(SupplyCorrectionService)는 요청 값으로 같은 규칙을 계산한다.
     */
    public static long householdTotal(Collection<SupplyTarget> targets) {
        Set<Long> counted = new HashSet<>();
        long total = 0;
        for (SupplyTarget target : targets) {
            if (target.isAdminExcluded()) { continue; }
            if (target.getSupplyHouseholdCount() != null) { total += target.getSupplyHouseholdCount(); }
            var group = target.getSupplyCountGroup();
            if (group != null && counted.add(group.getId())) { total += group.getHouseholdCount(); }
        }
        return total;
    }

    /** 공통 모집수의 종류. 공급세대수(우선·일반공급 모집수)와 이번 공고의 예비자 모집수다. */
    public enum Kind { SUPPLY, RESERVE }
}
