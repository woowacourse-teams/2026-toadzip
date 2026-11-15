package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 임대조건: 공고문 공급표의 금액 칸 한 세트(임대보증금·월임대료·전환보증금). 병합된 금액 칸은 멤버가 여럿이고, 칸 하나는 임대조건 하나만 가진다.
 * 금액이 같다는 이유만으로 묶지 않는다. 한 곳을 고칠 때 다른 단지 금액까지 바뀌기 때문이다.
 */
@Getter
@Entity
@Table(name = "lease_terms")
@NoArgsConstructor(access = PROTECTED)
public class LeaseTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Announcement announcement;

    private BigDecimal rentalDeposit;

    private BigDecimal monthlyRent;

    private BigDecimal convertedDeposit;

    @Column(nullable = false)
    private boolean adminModified;

    @Getter(AccessLevel.NONE)
    @ManyToMany(fetch = LAZY)
    @JoinTable(name = "lease_term_members",
            joinColumns = @JoinColumn(name = "lease_terms_id"),
            foreignKey = @ForeignKey(foreignKeyDefinition =
                    "FOREIGN KEY (lease_terms_id) REFERENCES lease_terms (id) ON DELETE CASCADE"),
            inverseJoinColumns = @JoinColumn(name = "supply_row_target_id"),
            inverseForeignKey = @ForeignKey(foreignKeyDefinition =
                    "FOREIGN KEY (supply_row_target_id) REFERENCES supply_row_targets (id) ON DELETE CASCADE"))
    private Set<SupplyRowTarget> members = new HashSet<>();

    private LeaseTerms(Announcement announcement, Amounts amounts, Collection<SupplyRowTarget> members) {
        if (announcement == null) {
            throw new IllegalArgumentException("임대조건의 공고는 필수다.");
        }
        this.announcement = announcement;
        apply(amounts);
        replaceMembers(members);
    }

    public static LeaseTerms create(Announcement announcement, Amounts amounts, Collection<SupplyRowTarget> members) {
        return new LeaseTerms(announcement, amounts, members);
    }

    public Set<SupplyRowTarget> getMembers() {
        return Set.copyOf(members);
    }

    public Amounts amounts() {
        return new Amounts(rentalDeposit, monthlyRent, convertedDeposit);
    }

    private void replaceMembers(Collection<SupplyRowTarget> next) {
        if (next == null || next.isEmpty()) {
            throw new IllegalArgumentException("임대조건에는 공급표 칸이 하나 이상 있어야 한다.");
        }
        for (SupplyRowTarget member : next) {
            Announcement owner = member.getSupplyRow().getAnnouncement();
            if (owner != announcement && (owner.getId() == null || !owner.getId().equals(announcement.getId()))) {
                throw new IllegalArgumentException("임대조건의 칸은 같은 공고여야 한다.");
            }
        }
        members.clear();
        members.addAll(next);
    }

    private void apply(Amounts amounts) {
        if (amounts == null || amounts.empty()) {
            throw new IllegalArgumentException("임대조건에는 금액이 하나 이상 있어야 한다.");
        }
        rentalDeposit = amounts.rentalDeposit();
        monthlyRent = amounts.monthlyRent();
        convertedDeposit = amounts.convertedDeposit();
    }

    /** 금액 칸 한 세트. 원 단위 정수이고 음수가 아니다. */
    public record Amounts(BigDecimal rentalDeposit, BigDecimal monthlyRent, BigDecimal convertedDeposit) {

        public Amounts {
            check(rentalDeposit, "임대보증금");
            check(monthlyRent, "월임대료");
            check(convertedDeposit, "전환보증금");
        }

        public boolean empty() {
            return rentalDeposit == null && monthlyRent == null && convertedDeposit == null;
        }

        private static void check(BigDecimal value, String name) {
            if (value == null) {
                return;
            }
            if (value.signum() < 0) {
                throw new IllegalArgumentException(name + "은 음수일 수 없다.");
            }
            try {
                value.longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(name + "은 Long 범위의 정수여야 한다.", exception);
            }
        }
    }
}
