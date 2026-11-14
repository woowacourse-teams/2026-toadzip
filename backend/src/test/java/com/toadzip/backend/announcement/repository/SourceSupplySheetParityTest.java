package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SourceSupplySheet;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정제·등록이 쓰는 공급표({@link SourceSupplySheet} + {@link SupplySheetStore})가 같은 공급을 옛 대상 테이블에 썼을 때 DB 트리거
 * (V20261113_02 supply_sheet_rebuild)가 만들던 공급표와 같은지 본다. ID는 달라도 칸·공급대상·수량 묶음·임대조건의 값과 멤버가 같아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceSupplySheetParityTest {

    @Autowired private SupplySheetStore store;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 대상이_여럿이거나_하나이거나_없거나_겹치거나_모집수를_넘는_공급행도_트리거와_같은_공급표를_쓴다() {
        List<Supply> supplies = List.of(
                // 나뉘지 않은 나머지 7은 행의 두 칸이 함께 쓴다. 신청 조건이 다르면 다른 공급대상이다.
                new Supply(10, List.of(new Line("청년", null, 3, 5, "1000000", "0", 0),
                        new Line("신혼부부", "소득 있음", null, null, null, null, 1))),
                // 대상 하나뿐인 행의 나머지 3은 '전체' 칸을 더해 함께 쓴다.
                new Supply(8, List.of(new Line("고령자", "공고문 참조", 5, null, null, null, 0))),
                // 대상이 없는 행은 '전체' 칸 하나가 모집수를 갖는다. 모집수 0도 0으로 남긴다.
                new Supply(6, List.of()),
                new Supply(0, List.of()),
                // 같은 대상이 두 번 나오면 먼저 나온 칸에 수를 더하고 금액은 먼저 나온 것을 쓴다. 합(5)이 모집수(4)보다 크면 나머지가 없다.
                new Supply(4, List.of(new Line("청년", null, 3, null, "2000000", "10000", 0),
                        new Line("청년", null, 2, 1, "9000000", "90000", 1))),
                // 모집수를 모르면 나머지가 없다. 앞 행에 나온 공급대상은 처음 나온 순서를 지킨다.
                new Supply(null, List.of(new Line("신혼부부", "소득 있음", 2, null, "5000000", null, 0),
                        new Line("청년", null, null, 4, null, null, 1))),
                // '전체' 대상 하나뿐이면 '전체' 칸을 더하지 않고 그 칸이 나머지를 갖는다.
                new Supply(5, List.of(new Line("전체", null, 2, null, null, "30000", 0))));

        Announcement triggerWritten = announcement();
        for (int order = 0; order < supplies.size(); order++) {
            SupplyRow row = row(triggerWritten, order, supplies.get(order).total());
            for (Line line : supplies.get(order).lines()) {
                entities.persist(SupplyTarget.create(row, line.label(), null, line.winner(), line.reserve(),
                        money(line.deposit()), money(line.rent()), null, line.condition(), line.order()));
            }
        }
        entities.flush();

        Announcement storeWritten = announcement();
        List<SourceSupplySheet.Row> rows = new ArrayList<>();
        for (int order = 0; order < supplies.size(); order++) {
            Supply supply = supplies.get(order);
            rows.add(new SourceSupplySheet.Row(row(storeWritten, order, supply.total()), supply.total(),
                    supply.lines().stream().map(line -> new SourceSupplySheet.Target(line.label(), line.condition(),
                            line.winner(), line.reserve(), new LeaseTerms.Amounts(money(line.deposit()),
                                    money(line.rent()), null), line.order())).toList()));
        }
        entities.flush();
        store.replace(storeWritten, SourceSupplySheet.draft(rows));
        entities.flush();

        assertThat(snapshot(storeWritten)).isEqualTo(snapshot(triggerWritten)).isNotEmpty();
        assertThat(snapshot(triggerWritten)).contains(
                "quota WINNER 3 [1/고령자/공고문 참조/0, 1/전체//1000] false",
                "quota WINNER 0 [3/전체//0] false",
                "lease 2000000.00/10000.00/null [4/청년//0] false");
    }

    /** 공급행 순번으로 쓴 칸·공급대상·수량 묶음·임대조건. 칸은 "행 순번/대상/조건/칸 순서"다. */
    private List<String> snapshot(Announcement announcement) {
        String cell = "r.display_order || '/' || t.label || '/' || t.application_condition || '/' || c.display_order";
        List<String> values = new ArrayList<>(jdbc.queryForList("""
                SELECT 'target ' || t.label || '/' || t.application_condition || ' ' || t.display_order
                    || ' ' || t.admin_modified
                FROM announcement_supply_targets t WHERE t.announcement_id = ? ORDER BY t.display_order, t.label
                """, String.class, announcement.getId()));
        values.addAll(jdbc.queryForList("""
                SELECT 'cell ' || %s FROM supply_row_targets c
                JOIN supply_rows r ON r.id = c.supply_row_id JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                WHERE r.announcement_id = ? ORDER BY r.display_order, c.display_order, t.display_order
                """.formatted(cell), String.class, announcement.getId()));
        values.addAll(jdbc.queryForList("""
                SELECT 'quota ' || q.kind || ' ' || q.household_count || ' [' || string_agg(%s, ', ' ORDER BY
                    r.display_order, c.display_order) || '] ' || q.admin_modified
                FROM supply_quotas q JOIN supply_quota_members m ON m.supply_quota_id = q.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id JOIN supply_rows r ON r.id = c.supply_row_id
                JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                WHERE q.announcement_id = ? GROUP BY q.id ORDER BY 1
                """.formatted(cell), String.class, announcement.getId()));
        values.addAll(jdbc.queryForList("""
                SELECT 'lease ' || COALESCE(l.rental_deposit::text, 'null') || '/' || COALESCE(l.monthly_rent::text, 'null')
                    || '/' || COALESCE(l.converted_deposit::text, 'null') || ' [' || string_agg(%s, ', ' ORDER BY
                    r.display_order, c.display_order) || '] ' || l.admin_modified
                FROM lease_terms l JOIN lease_term_members m ON m.lease_terms_id = l.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id JOIN supply_rows r ON r.id = c.supply_row_id
                JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                WHERE l.announcement_id = ? GROUP BY l.id ORDER BY 1
                """.formatted(cell), String.class, announcement.getId()));
        return values;
    }

    private Announcement announcement() {
        String suffix = UUID.randomUUID().toString();
        LocalDate today = LocalDate.of(2026, 11, 14);
        Announcement announcement = Announcement.create("source-" + suffix, null, null, "공고 " + suffix, "ORIGINAL",
                "행복주택", "신규모집", "LH", today, today, today.plusDays(3), today.plusMonths(1),
                "https://example.com/announcements/" + suffix, null, 0, null, null,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)));
        entities.persist(announcement);
        return announcement;
    }

    private SupplyRow row(Announcement announcement, int order, Integer householdCount) {
        SupplyRow row = SupplyRow.create(announcement, null, null, UUID.randomUUID().toString(), order, "단지",
                "36A-" + order, null, null, SupplyCategory.NEW_SUPPLY, null, householdCount);
        entities.persist(row);
        return row;
    }

    private static BigDecimal money(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private record Supply(Integer total, List<Line> lines) {
    }

    private record Line(String label, String condition, Integer winner, Integer reserve, String deposit, String rent,
            int order) {
    }
}
