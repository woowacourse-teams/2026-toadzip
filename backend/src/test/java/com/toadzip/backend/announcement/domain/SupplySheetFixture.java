package com.toadzip.backend.announcement.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

/** 저장소 없이 단위 테스트에서 공급표(대상·칸·수량 묶음·임대조건)를 만든다. ID는 1_000_000부터 붙인다. */
public final class SupplySheetFixture {

    private final Set<SupplyRow> rows = new LinkedHashSet<>();
    private final Map<String, AnnouncementSupplyTarget> targets = new HashMap<>();
    private final List<SupplyRowTarget> cells = new ArrayList<>();
    private final List<SupplyQuota> quotas = new ArrayList<>();
    private final List<LeaseTerms> leases = new ArrayList<>();
    private long nextId = 1_000_000;

    public SupplyRowTarget cell(SupplyRow row, String label, String condition, int order) {
        Announcement announcement = row.getAnnouncement();
        AnnouncementSupplyTarget target = targets.computeIfAbsent(
                announcement.getId() + "|" + AnnouncementSupplyTarget.key(label, condition),
                ignored -> withId(AnnouncementSupplyTarget.create(announcement, label, condition, targets.size())));
        rows.add(row);
        SupplyRowTarget cell = withId(SupplyRowTarget.create(row, target, order));
        cells.add(cell);
        return cell;
    }

    public SupplyRowTarget everyone(SupplyRow row) {
        return cell(row, AnnouncementSupplyTarget.EVERYONE, "", 1000);
    }

    public SupplyQuota quota(SupplyQuota.Kind kind, int count, SupplyRowTarget... members) {
        SupplyQuota quota = withId(SupplyQuota.create(members[0].getSupplyRow().getAnnouncement(), kind, count,
                List.of(members)));
        quotas.add(quota);
        return quota;
    }

    public LeaseTerms lease(BigDecimal deposit, BigDecimal monthlyRent, BigDecimal converted,
            SupplyRowTarget... members) {
        LeaseTerms lease = withId(LeaseTerms.create(members[0].getSupplyRow().getAnnouncement(),
                new LeaseTerms.Amounts(deposit, monthlyRent, converted), List.of(members)));
        leases.add(lease);
        return lease;
    }

    /** 대상 구분이 없는 행: '전체' 칸 하나와, 모집수가 있으면 그 칸만의 수량 묶음. */
    public SupplyRowTarget row(SupplyRow row, Integer winnerCount) {
        SupplyRowTarget cell = everyone(row);
        if (winnerCount != null) {
            quota(SupplyQuota.Kind.WINNER, winnerCount, cell);
        }
        return cell;
    }

    /** 칸 없는 공급행도 공급표에 넣는다. */
    public void add(SupplyRow row) {
        rows.add(row);
    }

    /** 공고 하나의 정본 공급표. */
    public SupplySheet sheet(long announcementId) {
        return SupplySheet.of(
                rows.stream().filter(row -> row.getAnnouncement().getId() == announcementId).toList(),
                cells.stream().filter(cell -> cell.getSupplyRow().getAnnouncement().getId() == announcementId).toList(),
                quotas.stream().filter(quota -> quota.getAnnouncement().getId() == announcementId).toList(),
                leases.stream().filter(lease -> lease.getAnnouncement().getId() == announcementId).toList());
    }

    private <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", nextId++);
        return entity;
    }
}
