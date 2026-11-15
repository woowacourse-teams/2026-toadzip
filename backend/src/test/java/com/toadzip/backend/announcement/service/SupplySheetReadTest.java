package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SourceSupplySheet;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.domain.SupplySheetDraft;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Cell;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Quota;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Target;
import com.toadzip.backend.announcement.repository.SupplySheetStore;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공급표 쓰기(SupplySheetStore)로 저장한 정본 공급표를 공급 읽기 입구(AnnouncementSupplies)로 공개 응답이 읽는 수(행 모집수, 칸만의 수, 함께 쓰는 수, 공고 합계)로
 * 읽는다. 정제·등록이 쓰는 규칙(SourceSupplySheet)으로 쓴 공급표와, 그 규칙이 만들지 않는 함께 쓰는 수량 묶음을 쓴 공급표를 함께 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupplySheetReadTest {

    @Autowired private EntityManager entities;
    @Autowired private AnnouncementSupplies supplies;
    @Autowired private SupplySheetStore store;

    @Test
    void 대상별로_나뉘지_않은_행_모집수는_행의_모든_칸이_함께_쓰고_대상_수에_더하지_않는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        writeSource(announcement, new SourceSupplySheet.Row(row, 10, List.of(target("청년", 3, 5, "1000000", 0),
                target("신혼부부", null, null, null, 1))));

        SupplySheet sheet = read(announcement, row);

        List<SupplySheet.Cell> cells = sheet.cells(row.getId());
        assertThat(labels(cells)).containsExactly("청년", "신혼부부");
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(10);
        assertThat(sheet.ownCount(cells.get(0), Kind.WINNER)).isEqualTo(3);
        assertThat(sheet.ownCount(cells.get(0), Kind.RESERVE)).isEqualTo(5);
        assertThat(sheet.ownCount(cells.get(1), Kind.WINNER)).isNull();
        assertThat(sheet.lease(cells.get(0))).isPresent();
        assertThat(sheet.lease(cells.get(1))).isEmpty();
        // 나머지 7은 두 대상이 함께 쓰는 행 모집수라 대상끼리 함께 쓰는 수로 보이지 않는다.
        assertThat(sheet.sharedInRow(cells.get(1), Kind.WINNER)).isEmpty();
        assertThat(sheet.total(Kind.WINNER)).isEqualTo(10);
    }

    @Test
    void 대상이_하나뿐인_행의_나머지는_전체_칸이_함께_써서_그_대상의_수로_읽히지_않는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        writeSource(announcement, new SourceSupplySheet.Row(row, 10, List.of(target("청년", 3, null, null, 0))));

        SupplySheet sheet = read(announcement, row);

        List<SupplySheet.Cell> cells = sheet.cells(row.getId());
        assertThat(labels(cells)).containsExactly("청년", "전체");
        assertThat(sheet.ownCount(cells.get(0), Kind.WINNER)).isEqualTo(3);
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(10);
    }

    @Test
    void 대상이_없는_행은_전체_칸이_행_모집수를_갖고_모집수를_모르는_행은_수가_없다() {
        Announcement announcement = announcement();
        SupplyRow plain = row(announcement, 0);
        SupplyRow unknown = row(announcement, 1);
        writeSource(announcement, new SourceSupplySheet.Row(plain, 8, List.of()),
                new SourceSupplySheet.Row(unknown, null, List.of()));

        SupplySheet sheet = read(announcement, plain, unknown);

        assertThat(labels(sheet.cells(plain.getId()))).containsExactly("전체");
        assertThat(sheet.ownCount(sheet.cells(plain.getId()).getFirst(), Kind.WINNER)).isEqualTo(8);
        assertThat(labels(sheet.cells(unknown.getId()))).containsExactly("전체");
        assertThat(sheet.rowCount(unknown.getId(), Kind.WINNER)).isNull();
        assertThat(sheet.total(Kind.WINNER)).isEqualTo(8);
        // 모집수를 모르는 행에는 수량 묶음을 남기지 않아 공고 단위로 묶음을 더해도 모르는 수가 0으로 들지 않는다.
        assertThat(((Number) entities.createNativeQuery("SELECT count(*) FROM supply_quotas WHERE announcement_id = :id")
                .setParameter("id", announcement.getId()).getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test
    void 대상별_수의_합이_행_모집수보다_크면_나머지를_만들지_않고_대상별_수의_합을_행_모집수로_읽는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        writeSource(announcement, new SourceSupplySheet.Row(row, 10, List.of(target("청년", 6, null, null, 0),
                target("신혼부부", 6, null, null, 1))));

        SupplySheet sheet = read(announcement, row);

        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(12);
    }

    @Test
    void 대상끼리_함께_쓰는_예비자_수는_묶음_하나이고_행의_모든_칸이_써도_함께_쓰는_수로_읽힌다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        store.replace(announcement, SupplySheetDraft.of(List.of(row.sourceSheetRow("h")),
                List.of(sheetTarget("t1", "청년(소득 없음)", 0), sheetTarget("t2", "청년(소득 있음)", 1)),
                List.of(new Cell("c1", "h", "t1", 0), new Cell("c2", "h", "t2", 1)),
                List.of(new Quota(Kind.RESERVE, 20, List.of("c1", "c2"), false)), List.of()));

        SupplySheet sheet = read(announcement, row);

        List<SupplySheet.Cell> cells = sheet.cells(row.getId());
        assertThat(sheet.sharedInRow(cells.get(0), Kind.RESERVE)).hasValueSatisfying(shared -> {
            assertThat(shared.count()).isEqualTo(20);
            assertThat(labels(shared.members())).containsExactly("청년(소득 없음)", "청년(소득 있음)");
        });
        assertThat(sheet.ownCount(cells.get(0), Kind.RESERVE)).isNull();
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isNull();
    }

    @Test
    void 여러_주택형이_함께_쓰는_모집수는_행마다_따로_세지_않고_공고_합계에_한_번만_더한다() {
        Announcement announcement = announcement();
        SupplyRow first = row(announcement, 0);
        SupplyRow second = row(announcement, 1);
        store.replace(announcement, SupplySheetDraft.of(List.of(first.sourceSheetRow("h1"), second.sourceSheetRow("h2")),
                List.of(sheetTarget("t", "전체", 0)),
                List.of(new Cell("c1", "h1", "t", 0), new Cell("c2", "h2", "t", 0)),
                List.of(new Quota(Kind.WINNER, 103, List.of("c1", "c2"), false)), List.of()));

        SupplySheet sheet = read(announcement, first, second);

        assertThat(sheet.sharedAcrossRows(first.getId(), Kind.WINNER)).hasValueSatisfying(
                quota -> assertThat(quota.count()).isEqualTo(103));
        assertThat(sheet.sharedAcrossRows(second.getId(), Kind.WINNER).map(SupplySheet.Quota::id))
                .isEqualTo(sheet.sharedAcrossRows(first.getId(), Kind.WINNER).map(SupplySheet.Quota::id));
        assertThat(sheet.rowCount(first.getId(), Kind.WINNER)).isNull();
        assertThat(sheet.total(Kind.WINNER)).isEqualTo(103);
    }

    @Test
    void 행의_일부_대상만_함께_쓰는_모집수는_그_대상들의_함께_쓰는_수로_읽히고_행_모집수에_한_번_든다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        store.replace(announcement, SupplySheetDraft.of(List.of(row.sourceSheetRow("h")),
                List.of(sheetTarget("t1", "청년", 0), sheetTarget("t2", "대학생", 1), sheetTarget("t3", "고령자", 2)),
                List.of(new Cell("c1", "h", "t1", 0), new Cell("c2", "h", "t2", 1), new Cell("c3", "h", "t3", 2)),
                List.of(new Quota(Kind.WINNER, 10, List.of("c1", "c2"), false),
                        new Quota(Kind.WINNER, 5, List.of("c3"), false)), List.of()));

        SupplySheet sheet = read(announcement, row);

        List<SupplySheet.Cell> cells = sheet.cells(row.getId());
        assertThat(sheet.sharedInRow(cells.get(0), Kind.WINNER)).hasValueSatisfying(shared -> {
            assertThat(shared.count()).isEqualTo(10);
            assertThat(labels(shared.members())).containsExactly("청년", "대학생");
        });
        assertThat(sheet.sharedInRow(cells.get(2), Kind.WINNER)).isEmpty();
        assertThat(sheet.ownCount(cells.get(2), Kind.WINNER)).isEqualTo(5);
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(15);
    }

    @Test
    void 다시_써도_칸_ID는_그대로이고_새_칸은_칸_표가_ID를_준다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 0);
        writeSource(announcement, new SourceSupplySheet.Row(row, 10, List.of(target("청년", 3, null, null, 0),
                target("신혼부부", 2, null, null, 1))));
        List<Long> before = read(announcement, row).cells(row.getId()).stream().map(SupplySheet.Cell::id).toList();

        SupplyRow reloaded = entities.find(SupplyRow.class, row.getId());
        writeSource(entities.find(Announcement.class, announcement.getId()), new SourceSupplySheet.Row(reloaded, 12,
                List.of(target("청년", 3, null, null, 0), target("신혼부부", 2, null, null, 1),
                        target("고령자", 1, null, null, 2))));
        SupplySheet sheet = read(announcement, row);

        List<Long> after = sheet.cells(row.getId()).stream().map(SupplySheet.Cell::id).toList();
        assertThat(after).hasSize(3).startsWith(before.toArray(Long[]::new));
        assertThat(after.get(2)).isNotNull().isNotIn(before);
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(12);
    }

    private void writeSource(Announcement announcement, SourceSupplySheet.Row... rows) {
        store.replace(announcement, SourceSupplySheet.draft(List.of(rows)));
    }

    private SupplySheet read(Announcement announcement, SupplyRow... rows) {
        entities.flush();
        entities.clear();
        var supply = supplies.read(entities.find(Announcement.class, announcement.getId()));
        assertThat(supply.confirmed()).isTrue();
        assertThat(supply.sheet().rows()).extracting(SupplySheet.Row::id)
                .containsExactly(java.util.Arrays.stream(rows).map(SupplyRow::getId).toArray(Long[]::new));
        return supply.sheet();
    }

    private static List<String> labels(List<SupplySheet.Cell> cells) {
        return cells.stream().map(SupplySheet.Cell::label).toList();
    }

    private static SourceSupplySheet.Target target(String label, Integer winner, Integer reserve, String deposit,
            int order) {
        BigDecimal amount = deposit == null ? null : new BigDecimal(deposit);
        return new SourceSupplySheet.Target(label, null, winner, reserve, new LeaseTerms.Amounts(amount, null, null),
                order);
    }

    private static Target sheetTarget(String key, String label, int order) {
        return new Target(key, label, null, order, false);
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

    private SupplyRow row(Announcement announcement, int order) {
        SupplyRow row = SupplyRow.create(announcement, null, null, UUID.randomUUID().toString(), order, "단지",
                "36A-" + order, null, null, SupplyCategory.NEW_SUPPLY, null);
        entities.persist(row);
        entities.flush();
        return row;
    }
}
