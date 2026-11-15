package com.toadzip.backend.announcement.domain;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;

/**
 * DB 테스트에서 저장한 공급행에 공급표(공급대상·칸·수량 묶음·임대조건)를 바로 넣는다. 공고 안에서 (표기, 신청 조건)이 같은 공급대상은
 * 이미 있으면 그것을 쓴다. 정제·등록이 쓰는 규칙(SourceSupplySheet)은 거치지 않으므로 넣은 그대로 읽힌다.
 */
public final class SupplySheetSeed {

    private final EntityManager entities;

    public SupplySheetSeed(EntityManager entities) {
        this.entities = entities;
    }

    /** 대상 하나의 칸과, 있으면 그 칸만의 당첨 수·예비 수·임대조건. */
    public SupplyRowTarget target(SupplyRow row, String label, String condition, Integer winnerCount,
            Integer reserveCount, BigDecimal deposit, BigDecimal monthlyRent, BigDecimal convertedDeposit, int order) {
        SupplyRowTarget cell = cell(row, label, condition, order);
        if (winnerCount != null) {
            quota(SupplyQuota.Kind.WINNER, winnerCount, cell);
        }
        if (reserveCount != null) {
            quota(SupplyQuota.Kind.RESERVE, reserveCount, cell);
        }
        LeaseTerms.Amounts amounts = new LeaseTerms.Amounts(deposit, monthlyRent, convertedDeposit);
        if (!amounts.empty()) {
            lease(amounts, cell);
        }
        return cell;
    }

    /** 대상 구분이 없는 행: '전체' 칸 하나와, 모집수가 있으면 그 칸만의 당첨 수. */
    public SupplyRowTarget row(SupplyRow row, Integer winnerCount) {
        return target(row, AnnouncementSupplyTarget.EVERYONE, null, winnerCount, null, null, null, null, 1000);
    }

    public SupplyRowTarget cell(SupplyRow row, String label, String condition, int order) {
        SupplyRowTarget cell = SupplyRowTarget.create(row, supplyTarget(row.getAnnouncement(), label, condition), order);
        entities.persist(cell);
        return cell;
    }

    public SupplyQuota quota(SupplyQuota.Kind kind, int count, SupplyRowTarget... members) {
        SupplyQuota quota = SupplyQuota.create(members[0].getSupplyRow().getAnnouncement(), kind, count,
                List.of(members));
        entities.persist(quota);
        return quota;
    }

    public LeaseTerms lease(LeaseTerms.Amounts amounts, SupplyRowTarget... members) {
        LeaseTerms lease = LeaseTerms.create(members[0].getSupplyRow().getAnnouncement(), amounts, List.of(members));
        entities.persist(lease);
        return lease;
    }

    private AnnouncementSupplyTarget supplyTarget(Announcement announcement, String label, String condition) {
        String normalized = condition == null ? "" : condition;
        entities.flush();
        List<AnnouncementSupplyTarget> stored = entities.createQuery("""
                SELECT target FROM AnnouncementSupplyTarget target
                WHERE target.announcement = :announcement AND target.label = :label
                  AND target.applicationCondition = :condition
                """, AnnouncementSupplyTarget.class).setParameter("announcement", announcement)
                .setParameter("label", label).setParameter("condition", normalized).getResultList();
        if (!stored.isEmpty()) {
            return stored.getFirst();
        }
        long order = entities.createQuery(
                "SELECT count(target) FROM AnnouncementSupplyTarget target WHERE target.announcement = :announcement",
                Long.class).setParameter("announcement", announcement).getSingleResult();
        AnnouncementSupplyTarget target = AnnouncementSupplyTarget.create(announcement, label, normalized, (int) order);
        entities.persist(target);
        return target;
    }
}
