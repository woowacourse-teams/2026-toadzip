package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SourceSupplySheet;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.domain.SupplySheetDraft;
import com.toadzip.backend.announcement.repository.SupplySheetStore;
import com.toadzip.backend.housing.domain.AgencyCode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공급 읽기 입구: 정본을 확정한 공고는 공고 공급표(정본), 확정하기 전 공고는 기관 준비 줄을 공급표로 바꾼 것을 보인다. 확정은 정본 행이
 * 있는지가 아니라 확정 시각으로 정한다. 정본 행을 다 지운 확정 공고도 준비 데이터로 돌아가지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnnouncementSuppliesIntegrationTest {

    @Autowired private EntityManager entities;
    @Autowired private AnnouncementSupplies supplies;
    @Autowired private SupplySheetStore store;

    @Test
    void 확정_전_공고는_준비_줄을_LH_신청형으로_묶어_공급표로_보인다() {
        Announcement staged = announcement();
        AnnouncementComplex complex = complex(staged, "행복단지");
        line(staged, complex, "LH:1", 0, "26A(청년)", 5, "1000", "10");
        line(staged, complex, "LH:2", 1, "26A(고령자)", 3, null, null);
        line(staged, complex, "LH:3", 2, "36A", 7, null, null);

        AnnouncementSupplies.AnnouncementSupply supply = read(staged);

        assertThat(supply.confirmed()).isFalse();
        SupplySheet sheet = supply.sheet();
        assertThat(sheet.rows()).extracting(SupplySheet.Row::sourceHousingTypeName)
                .containsExactly("26A(청년) · 26A(고령자)", "36A");
        SupplySheet.Row joint = sheet.rows().getFirst();
        List<SupplySheet.Cell> cells = sheet.cells(joint.id());
        assertThat(cells).extracting(SupplySheet.Cell::label).containsExactly("청년", "고령자");
        assertThat(sheet.ownCount(cells.get(0), Kind.WINNER)).isEqualTo(5);
        assertThat(sheet.lease(cells.get(0))).map(lease -> lease.amounts().rentalDeposit().intValue()).contains(1000);
        assertThat(sheet.rowCount(joint.id(), Kind.WINNER)).isEqualTo(8);
        assertThat(sheet.total(Kind.WINNER)).isEqualTo(15);
    }

    @Test
    void 확정한_공고는_준비_줄이_있어도_정본_값을_보인다() {
        Announcement confirmed = announcement();
        AnnouncementComplex complex = complex(confirmed, "행복단지");
        line(confirmed, complex, "LH:1", 0, "26A(청년)", 5, "1000", "10");
        SupplyRow row = SupplyRowSeed.create(entities, confirmed, null, null, 0, "행복단지", "26A", null, null,
                SupplyCategory.NEW_SUPPLY);
        entities.persist(row);
        entities.flush();
        store.replace(confirmed, SourceSupplySheet.draft(List.of(SupplyRowSeed.sourceRow(row, 9, List.of(
                new SourceSupplySheet.Target("청년", null, 9, null,
                        new LeaseTerms.Amounts(new BigDecimal("2000"), null, null), 0))))));

        AnnouncementSupplies.AnnouncementSupply supply = read(confirmed);

        assertThat(supply.confirmed()).isTrue();
        assertThat(supply.sheet().rows()).extracting(SupplySheet.Row::id).containsExactly(row.getId());
        assertThat(supply.sheet().total(Kind.WINNER)).isEqualTo(9);
    }

    @Test
    void 정본_행을_모두_지운_확정_공고는_준비_줄로_돌아가지_않고_빈_공급표다() {
        Announcement emptied = announcement();
        AnnouncementComplex complex = complex(emptied, "행복단지");
        line(emptied, complex, "LH:1", 0, "26A(청년)", 5, "1000", "10");
        SupplyRow row = SupplyRowSeed.create(entities, emptied, null, null, 0, "행복단지", "26A", null, null,
                SupplyCategory.NEW_SUPPLY);
        entities.persist(row);
        entities.flush();
        store.replace(emptied, SourceSupplySheet.draft(List.of(SupplyRowSeed.sourceRow(row, 9, List.of()))));
        store.replace(emptied, SupplySheetDraft.of(List.of(), List.of(), List.of(), List.of(), List.of()));

        AnnouncementSupplies.AnnouncementSupply supply = read(emptied);

        assertThat(supply.confirmed()).isTrue();
        assertThat(supply.sheet().rows()).isEmpty();
        assertThat(supply.sheet().total(Kind.WINNER)).isNull();
    }

    @Test
    void 여러_공고를_한_번에_읽어도_공고마다_확정에_따라_정본과_준비_데이터를_나눈다() {
        Announcement staged = announcement();
        line(staged, complex(staged, "준비단지"), "LH:1", 0, "36A", 4, null, null);
        Announcement confirmed = announcement();
        SupplyRow row = SupplyRowSeed.create(entities, confirmed, null, null, 0, "정본단지", "26A", null, null,
                SupplyCategory.NEW_SUPPLY);
        entities.persist(row);
        entities.flush();
        store.replace(confirmed, SourceSupplySheet.draft(List.of(SupplyRowSeed.sourceRow(row, 2, List.of()))));
        Announcement nothing = announcement();
        entities.flush();
        entities.clear();

        Map<Long, AnnouncementSupplies.AnnouncementSupply> read = supplies.read(List.of(
                entities.find(Announcement.class, staged.getId()), entities.find(Announcement.class, confirmed.getId()),
                entities.find(Announcement.class, nothing.getId())));

        assertThat(read.get(staged.getId()).confirmed()).isFalse();
        assertThat(read.get(staged.getId()).sheet().rows()).extracting(SupplySheet.Row::sourceComplexName)
                .containsExactly("준비단지");
        assertThat(read.get(confirmed.getId()).confirmed()).isTrue();
        assertThat(read.get(confirmed.getId()).sheet().rows()).extracting(SupplySheet.Row::sourceComplexName)
                .containsExactly("정본단지");
        assertThat(read.get(nothing.getId()).confirmed()).isFalse();
        assertThat(read.get(nothing.getId()).sheet().rows()).isEmpty();
    }

    private AnnouncementSupplies.AnnouncementSupply read(Announcement announcement) {
        entities.flush();
        entities.clear();
        return supplies.read(entities.find(Announcement.class, announcement.getId()));
    }

    private AnnouncementComplex complex(Announcement announcement, String name) {
        AnnouncementComplex complex = AnnouncementComplex.observe(announcement, name, null, null);
        entities.persist(complex);
        return complex;
    }

    private void line(Announcement announcement, AnnouncementComplex complex, String key, int order, String typeName,
            Integer count, String deposit, String rent) {
        entities.persist(AgencySupplyLine.create(announcement, AgencyCode.LH, new AgencySupplyLine.Values(key, order,
                complex, complex.getSourceComplexName(), typeName, null, null, null, count,
                deposit == null ? null : new BigDecimal(deposit), rent == null ? null : new BigDecimal(rent), null,
                SupplyCategory.NEW_SUPPLY)));
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
}
