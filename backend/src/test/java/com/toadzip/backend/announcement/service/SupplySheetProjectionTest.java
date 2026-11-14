package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import com.toadzip.backend.announcement.domain.SupplyRowTarget;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.domain.SupplyTargetCountGroup;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 옛 대상·수 테이블에 쓰면 DB 트리거(V20261113_02)가 공급표(칸·수량 묶음·임대조건)를 다시 만든다.
 * 공개 응답과 신청자격 초안이 읽는 수가 옛 테이블의 뜻과 같은지 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SupplySheets.class)
class SupplySheetProjectionTest {

    @Autowired
    private EntityManager entities;

    @Autowired
    private SupplySheets sheets;

    @Test
    void 대상별로_나뉘지_않은_행_모집수는_행의_모든_칸이_함께_쓰고_대상_수에_더하지_않는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        target(row, "청년", 3, 5, "1000000", 0);
        target(row, "신혼부부", null, null, null, 1);

        SupplySheet sheet = read(announcement, row);

        List<SupplyRowTarget> cells = sheet.cells(row.getId());
        assertThat(labels(cells)).containsExactly("청년", "신혼부부");
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(10);
        assertThat(sheet.ownCount(cells.get(0), Kind.WINNER)).isEqualTo(3);
        assertThat(sheet.ownCount(cells.get(0), Kind.RESERVE)).isEqualTo(5);
        assertThat(sheet.ownCount(cells.get(1), Kind.WINNER)).isNull();
        assertThat(sheet.lease(cells.get(0))).isPresent();
        assertThat(sheet.lease(cells.get(1))).isEmpty();
        // 나머지 7은 두 대상이 함께 쓰는 행 모집수라 대상끼리 함께 쓰는 수로 보이지 않는다.
        assertThat(sheet.sharedInRow(cells.get(1), Kind.WINNER)).isEmpty();
        assertThat(sheet.total(Kind.WINNER, id -> true)).isEqualTo(10);
    }

    @Test
    void 대상이_하나뿐인_행의_나머지는_전체_칸이_함께_써서_그_대상의_수로_읽히지_않는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        target(row, "청년", 3, null, null, 0);

        SupplySheet sheet = read(announcement, row);

        List<SupplyRowTarget> cells = sheet.cells(row.getId());
        assertThat(labels(cells)).containsExactly("청년", "전체");
        assertThat(sheet.ownCount(cells.get(0), Kind.WINNER)).isEqualTo(3);
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(10);
    }

    @Test
    void 대상이_없는_행은_전체_칸이_행_모집수를_갖고_대상을_모두_뺀_행은_칸도_수도_없다() {
        Announcement announcement = announcement();
        SupplyRow plain = row(announcement, 8);
        SupplyRow excluded = row(announcement, 6);
        SupplyTarget only = target(excluded, "청년", 6, null, null, 0);
        entities.flush();
        entities.createNativeQuery("UPDATE supply_targets SET admin_excluded = true WHERE id = :id")
                .setParameter("id", only.getId()).executeUpdate();

        SupplySheet sheet = read(announcement, plain, excluded);

        assertThat(labels(sheet.cells(plain.getId()))).containsExactly("전체");
        assertThat(sheet.ownCount(sheet.cells(plain.getId()).getFirst(), Kind.WINNER)).isEqualTo(8);
        assertThat(sheet.cells(excluded.getId())).isEmpty();
        assertThat(sheet.rowCount(excluded.getId(), Kind.WINNER)).isNull();
        assertThat(sheet.total(Kind.WINNER, id -> true)).isEqualTo(8);
        // 공고 단위로 수량 묶음을 더하는 쪽이 생겨도 뺀 행의 모집수가 들어가지 않게 멤버 없는 묶음을 남기지 않는다.
        assertThat(((Number) entities.createNativeQuery("SELECT count(*) FROM supply_quotas WHERE announcement_id = :id")
                .setParameter("id", announcement.getId()).getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test
    void 한_트랜잭션에서_옛_테이블을_여러_번_고쳐도_다시_만들기의_잠금이_쌓이지_않는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        SupplyTarget youth = target(row, "청년", 3, null, null, 0);
        read(announcement, row);

        for (int count = 0; count < 200; count++) {
            entities.createNativeQuery("UPDATE supply_targets SET supply_household_count = :count WHERE id = :id")
                    .setParameter("count", count).setParameter("id", youth.getId()).executeUpdate();
        }

        long locks = ((Number) entities.createNativeQuery(
                "SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid()").getSingleResult()).longValue();
        assertThat(locks).isLessThan(300);
        SupplySheet sheet = read(announcement, row);
        assertThat(sheet.ownCount(sheet.cells(row.getId()).getFirst(), Kind.WINNER)).isEqualTo(199);
    }

    @Test
    void 대상끼리_함께_쓰는_예비자_수는_묶음_하나이고_행의_모든_칸이_써도_함께_쓰는_수로_읽힌다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, null);
        SupplyTarget first = target(row, "청년(소득 없음)", null, null, null, 0);
        SupplyTarget second = target(row, "청년(소득 있음)", null, null, null, 1);
        SupplyTargetCountGroup reserve = SupplyTargetCountGroup.create(row, SupplyTargetCountGroup.Kind.RESERVE, 20);
        entities.persist(reserve);
        first.shareCounts(null, reserve);
        second.shareCounts(null, reserve);

        SupplySheet sheet = read(announcement, row);

        List<SupplyRowTarget> cells = sheet.cells(row.getId());
        assertThat(sheet.sharedInRow(cells.get(0), Kind.RESERVE)).hasValueSatisfying(shared -> {
            assertThat(shared.count()).isEqualTo(20);
            assertThat(labels(shared.members())).containsExactly("청년(소득 없음)", "청년(소득 있음)");
        });
        assertThat(sheet.ownCount(cells.get(0), Kind.RESERVE)).isNull();
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isNull();
    }

    @Test
    void 옛_테이블을_고치거나_행을_지우면_공급표도_따라_바뀐다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        SupplyTarget youth = target(row, "청년", 3, null, null, 0);
        SupplyRow other = row(announcement, 4);
        read(announcement, row, other);

        entities.createNativeQuery("UPDATE supply_targets SET supply_household_count = 7 WHERE id = :id")
                .setParameter("id", youth.getId()).executeUpdate();
        SupplySheet changed = read(announcement, row, other);
        assertThat(changed.ownCount(changed.cells(row.getId()).getFirst(), Kind.WINNER)).isEqualTo(7);

        entities.createNativeQuery("DELETE FROM supply_targets WHERE supply_row_id = :id")
                .setParameter("id", row.getId()).executeUpdate();
        entities.createNativeQuery("DELETE FROM supply_rows WHERE id = :id")
                .setParameter("id", row.getId()).executeUpdate();
        SupplySheet removed = read(announcement, row, other);
        assertThat(removed.cells(row.getId())).isEmpty();
        assertThat(removed.total(Kind.WINNER, id -> true)).isEqualTo(4);
    }

    @Test
    void 여러_주택형이_함께_쓰는_모집수는_행마다_따로_세지_않고_공고_합계에_한_번만_더한다() {
        Announcement announcement = announcement();
        SupplyRowGroup group = SupplyRowGroup.create(announcement, UUID.randomUUID().toString());
        group.confirm(103, "f".repeat(64), "https://example.com/notice.pdf", "병합 칸", "admin");
        entities.persist(group);
        SupplyRow first = row(announcement, null);
        SupplyRow second = row(announcement, null);
        first.protectInGroup(group, "fingerprint");
        second.protectInGroup(group, "fingerprint");

        SupplySheet sheet = read(announcement, first, second);

        assertThat(sheet.sharedAcrossRows(first.getId(), Kind.WINNER)).hasValueSatisfying(
                quota -> assertThat(quota.getHouseholdCount()).isEqualTo(103));
        assertThat(sheet.sharedAcrossRows(second.getId(), Kind.WINNER).map(quota -> quota.getId()))
                .isEqualTo(sheet.sharedAcrossRows(first.getId(), Kind.WINNER).map(quota -> quota.getId()));
        assertThat(sheet.rowCount(first.getId(), Kind.WINNER)).isNull();
        assertThat(sheet.total(Kind.WINNER, id -> true)).isEqualTo(103);
    }

    @Test
    void 행의_일부_대상만_함께_쓰는_모집수는_그_대상들의_함께_쓰는_수로_읽히고_행_모집수에_한_번_든다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 15);
        SupplyTarget first = target(row, "청년", null, null, null, 0);
        SupplyTarget second = target(row, "대학생", null, null, null, 1);
        target(row, "고령자", 5, null, null, 2);
        SupplyTargetCountGroup supply = SupplyTargetCountGroup.create(row, SupplyTargetCountGroup.Kind.SUPPLY, 10);
        entities.persist(supply);
        first.shareCounts(supply, null);
        second.shareCounts(supply, null);

        SupplySheet sheet = read(announcement, row);

        List<SupplyRowTarget> cells = sheet.cells(row.getId());
        assertThat(sheet.sharedInRow(cells.get(0), Kind.WINNER)).hasValueSatisfying(shared -> {
            assertThat(shared.count()).isEqualTo(10);
            assertThat(labels(shared.members())).containsExactly("청년", "대학생");
        });
        assertThat(sheet.sharedInRow(cells.get(2), Kind.WINNER)).isEmpty();
        assertThat(sheet.ownCount(cells.get(2), Kind.WINNER)).isEqualTo(5);
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(15);
    }

    @Test
    void 대상별_수의_합이_행_모집수보다_크면_나머지를_만들지_않고_대상별_수의_합을_행_모집수로_읽는다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        target(row, "청년", 6, null, null, 0);
        target(row, "신혼부부", 6, null, null, 1);

        SupplySheet sheet = read(announcement, row);

        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(12);
    }

    @Test
    void 다시_만들어도_칸_ID는_옛_대상_ID를_그대로_쓴다() {
        Announcement announcement = announcement();
        SupplyRow row = row(announcement, 10);
        SupplyTarget youth = target(row, "청년", 3, null, null, 0);
        SupplyTarget newlywed = target(row, "신혼부부", 2, null, null, 1);
        read(announcement, row);

        entities.createNativeQuery("UPDATE supply_rows SET total_supply_household_count = 12 WHERE id = :id")
                .setParameter("id", row.getId()).executeUpdate();
        SupplySheet sheet = read(announcement, row);

        assertThat(sheet.cells(row.getId()).stream().map(SupplyRowTarget::getId).toList())
                .containsExactly(youth.getId(), newlywed.getId());
        assertThat(sheet.rowCount(row.getId(), Kind.WINNER)).isEqualTo(12);
    }

    private SupplySheet read(Announcement announcement, SupplyRow... rows) {
        entities.flush();
        entities.clear();
        return sheets.read(List.of(announcement.getId()), java.util.Arrays.stream(rows).map(SupplyRow::getId).toList());
    }

    private static List<String> labels(List<SupplyRowTarget> cells) {
        return cells.stream().map(cell -> cell.getTarget().getLabel()).toList();
    }

    private Announcement announcement() {
        String suffix = UUID.randomUUID().toString();
        LocalDate today = LocalDate.of(2026, 11, 13);
        Announcement announcement = Announcement.create("source-" + suffix, null, null, "공고 " + suffix, "ORIGINAL",
                "행복주택", "신규모집", "LH", today, today, today.plusDays(3), today.plusMonths(1),
                "https://example.com/announcements/" + suffix, null, 0, null, null,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)));
        entities.persist(announcement);
        return announcement;
    }

    private SupplyRow row(Announcement announcement, Integer householdCount) {
        SupplyRow row = SupplyRow.create(announcement, null, null, UUID.randomUUID().toString(), 0, "단지", "36A",
                null, null, SupplyCategory.NEW_SUPPLY, null, householdCount);
        entities.persist(row);
        return row;
    }

    private SupplyTarget target(SupplyRow row, String label, Integer count, Integer reserve, String deposit, int order) {
        BigDecimal amount = deposit == null ? null : new BigDecimal(deposit);
        SupplyTarget target = SupplyTarget.create(row, label, null, count, reserve, amount, null, null, null, order);
        entities.persist(target);
        return target;
    }
}
