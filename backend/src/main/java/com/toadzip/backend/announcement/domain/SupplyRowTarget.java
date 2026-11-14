package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 공급표 칸 하나: 이 공고 주택형(공급행)에 이 공급대상이 있다. 수량 묶음(SupplyQuota)과 임대조건(LeaseTerms)은 칸을 멤버로 가진다.
 * 수나 금액이 없는 칸도 남아 "이 형에 이 대상이 있다"를 잃지 않는다.
 */
@Getter
@Entity
@Table(name = "supply_row_targets", uniqueConstraints = @UniqueConstraint(name = "uk_supply_row_targets",
        columnNames = {"supply_row_id", "supply_target_id"}))
@NoArgsConstructor(access = PROTECTED)
public class SupplyRowTarget {

    /** 옛 대상에서 온 칸은 옛 대상 ID, 그 밖의 칸은 옛 대상 시퀀스에서 받은 ID다(공급표 다시 만들기가 정한다). */
    @Id
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "supply_row_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SupplyRow supplyRow;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "supply_target_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AnnouncementSupplyTarget target;

    @Column(nullable = false)
    private int displayOrder;

    private SupplyRowTarget(SupplyRow supplyRow, AnnouncementSupplyTarget target, int displayOrder) {
        if (supplyRow == null || target == null) {
            throw new IllegalArgumentException("공급표 칸의 공급행과 공급대상은 필수다.");
        }
        if (!sameAnnouncement(supplyRow.getAnnouncement(), target.getAnnouncement())) {
            throw new IllegalArgumentException("공급표 칸의 공급행과 공급대상은 같은 공고여야 한다.");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("표시 순서는 음수일 수 없다.");
        }
        this.supplyRow = supplyRow;
        this.target = target;
        this.displayOrder = displayOrder;
    }

    public static SupplyRowTarget create(SupplyRow supplyRow, AnnouncementSupplyTarget target, int displayOrder) {
        return new SupplyRowTarget(supplyRow, target, displayOrder);
    }

    private static boolean sameAnnouncement(Announcement left, Announcement right) {
        return left == right || left.getId() != null && left.getId().equals(right.getId());
    }
}
