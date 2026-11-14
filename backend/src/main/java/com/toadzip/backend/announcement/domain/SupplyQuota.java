package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 수량 묶음: 공고문 공급표의 수량 칸 하나. 칸이 여러 주택형·대상에 걸쳐 병합되어 있으면 멤버(공급표 칸)가 여럿이다
 * (예: 55A·55B·55C 당첨 103). 같은 수를 멤버마다 나누거나 복제하지 않고 여기에 한 번만 둔다.
 */
@Getter
@Entity
@Table(name = "supply_quotas")
@NoArgsConstructor(access = PROTECTED)
public class SupplyQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Announcement announcement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false)
    private int householdCount;

    @Column(nullable = false)
    private boolean adminModified;

    @Getter(AccessLevel.NONE)
    @ManyToMany(fetch = LAZY)
    @JoinTable(name = "supply_quota_members",
            joinColumns = @JoinColumn(name = "supply_quota_id"),
            foreignKey = @ForeignKey(foreignKeyDefinition =
                    "FOREIGN KEY (supply_quota_id) REFERENCES supply_quotas (id) ON DELETE CASCADE"),
            inverseJoinColumns = @JoinColumn(name = "supply_row_target_id"),
            inverseForeignKey = @ForeignKey(foreignKeyDefinition =
                    "FOREIGN KEY (supply_row_target_id) REFERENCES supply_row_targets (id) ON DELETE CASCADE"))
    private Set<SupplyRowTarget> members = new HashSet<>();

    private SupplyQuota(Announcement announcement, Kind kind, int householdCount, Collection<SupplyRowTarget> members) {
        if (announcement == null || kind == null) {
            throw new IllegalArgumentException("수량 묶음의 공고와 종류는 필수다.");
        }
        this.announcement = announcement;
        this.kind = kind;
        this.householdCount = count(householdCount);
        replaceMembers(members);
    }

    public static SupplyQuota create(Announcement announcement, Kind kind, int householdCount,
            Collection<SupplyRowTarget> members) {
        return new SupplyQuota(announcement, kind, householdCount, members);
    }

    public Set<SupplyRowTarget> getMembers() {
        return Set.copyOf(members);
    }

    private void replaceMembers(Collection<SupplyRowTarget> next) {
        if (next == null || next.isEmpty()) {
            throw new IllegalArgumentException("수량 묶음에는 공급표 칸이 하나 이상 있어야 한다.");
        }
        for (SupplyRowTarget member : next) {
            Announcement owner = member.getSupplyRow().getAnnouncement();
            if (owner != announcement && (owner.getId() == null || !owner.getId().equals(announcement.getId()))) {
                throw new IllegalArgumentException("수량 묶음의 칸은 같은 공고여야 한다.");
            }
        }
        members.clear();
        members.addAll(next);
    }

    private static int count(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("모집수는 음수일 수 없다.");
        }
        return value;
    }

    /** WINNER는 모집(당첨) 세대수(우선공급과 일반공급의 합), RESERVE는 예비자 수다. */
    public enum Kind { WINNER, RESERVE }
}
