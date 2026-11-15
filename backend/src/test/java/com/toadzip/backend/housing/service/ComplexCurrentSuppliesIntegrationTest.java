package com.toadzip.backend.housing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.domain.SupplyRowTarget;
import com.toadzip.backend.announcement.domain.SupplySheetSeed;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.housing.repository.CurrentAnnouncementTargetRow;
import com.toadzip.backend.housing.repository.CurrentSupplyConditionRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단지 상세의 현재 공고 공급조건·공급대상. 현재 공고는 단지 상세 현재 공고 목록이고, 공고 공급은 공급 읽기 입구로 읽는다: 확정한 공고는
 * 공고 공급표(정본), 확정 전 공고는 기관 준비 줄이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ComplexCurrentSuppliesIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    @Autowired private EntityManager entityManager;
    @Autowired private ComplexDetailQueryRepository repository;
    @Autowired private ComplexCurrentSupplies currentSupplies;

    @Test
    void 현재공고_대상은_공고별_distinct와_표시순서를_보장한다() {
        HousingComplex complex = persistComplex("공고 대상 단지");
        HousingType housingType = persistHousingType(complex, "36A", "36.12");
        Announcement newer = persistAnnouncement(null, "ORIGINAL", "newer", TODAY, TODAY, TODAY.plusDays(2));
        SupplyRow laterRow = persistSupplyRow(newer, complex, housingType, "later-row", 2);
        persistSupplyTarget(laterRow, "청년", "3", "300", null, 1);
        SupplyRow firstRow = persistSupplyRow(newer, complex, housingType, "first-row", 1);
        SupplyRowTarget first = persistSupplyTarget(firstRow, "신혼부부", "1", "100", null, 2);
        SupplyRowTarget second = persistSupplyTarget(firstRow, "청년", "2", "200", null, 3);
        Announcement older = persistAnnouncement(null, "ORIGINAL", "older", TODAY.minusDays(1), TODAY,
                TODAY.plusDays(1));
        SupplyRow olderRow = persistSupplyRow(older, complex, housingType, "older-row", 1);
        SupplyRowTarget olderTarget = persistSupplyTarget(olderRow, "고령자", "4", "400", null, 1);
        entityManager.flush();

        assertEquals(List.of(
                new CurrentAnnouncementTargetRow(newer.getId(), firstRow.getId(), first.getId(), "신혼부부"),
                new CurrentAnnouncementTargetRow(newer.getId(), firstRow.getId(), second.getId(), "청년"),
                new CurrentAnnouncementTargetRow(older.getId(), olderRow.getId(), olderTarget.getId(), "고령자")
        ), read(complex).targets());
    }

    @Test
    void 현재공급조건은_미매칭_종료_취소를_제외하고_순서를_보장한다() {
        HousingComplex complex = persistComplex("공급 조건 단지");
        HousingType firstType = persistHousingType(complex, "36A", "36.12");
        HousingType secondType = persistHousingType(complex, "44B", "44.87");
        Announcement active = persistAnnouncement(null, "ORIGINAL", "active", TODAY, TODAY, TODAY.plusDays(2));
        SupplyRow laterRow = persistSupplyRow(active, complex, secondType, "later-row", 2);
        SupplyRowTarget laterTarget = persistSupplyTarget(laterRow, "신혼부부", "30", "300", "3000", 1);
        SupplyRow firstRow = persistSupplyRow(active, complex, firstType, "first-row", 1);
        SupplyRowTarget secondTarget = persistSupplyTarget(firstRow, "청년", "20", "200", null, 2);
        SupplyRowTarget firstTarget = persistSupplyTarget(firstRow, "대학생", "10", "100", "1000", 1);
        SupplyRow missingType = persistSupplyRow(active, complex, null, "missing-type", 3);
        persistSupplyTarget(missingType, "주택형 미매칭", "40", "400", null, 1);
        SupplyRow missingComplex = persistUnmatchedSupplyRow(active, "missing-complex");
        persistSupplyTarget(missingComplex, "단지 미매칭", "50", "500", null, 1);

        Announcement ended = persistAnnouncement(null, "ORIGINAL", "ended", TODAY.minusDays(1), TODAY.minusDays(3),
                TODAY.minusDays(1));
        SupplyRow endedRow = persistSupplyRow(ended, complex, firstType, "ended-row", 1);
        persistSupplyTarget(endedRow, "종료", "60", "600", null, 1);
        Announcement original = persistAnnouncement(null, "ORIGINAL", "original", TODAY.minusDays(2), TODAY,
                TODAY.plusDays(3));
        SupplyRow originalRow = persistSupplyRow(original, complex, firstType, "original-row", 1);
        persistSupplyTarget(originalRow, "원공고", "70", "700", null, 1);
        Announcement cancellation = persistAnnouncement(original, "취소공고", "cancellation", TODAY.plusDays(1), TODAY,
                TODAY.plusDays(3));
        SupplyRow cancellationRow = persistSupplyRow(cancellation, complex, firstType, "cancellation-row", 1);
        persistSupplyTarget(cancellationRow, "취소공고", "80", "800", null, 1);
        entityManager.flush();

        assertEquals(List.of(
                condition(active, firstRow, firstType, firstTarget, "10", "100", "1000"),
                condition(active, firstRow, firstType, secondTarget, "20", "200", null),
                condition(active, laterRow, secondType, laterTarget, "30", "300", "3000")
        ), read(complex).conditions());
    }

    @Test
    void 현재공급조건은_여러_주택형_행이_함께_쓰는_임대조건을_행마다_한_번씩_보인다() {
        HousingComplex complex = persistComplex("세부 타입 단지");
        HousingType typeA = persistHousingType(complex, "26A", "26.46");
        HousingType typeB = persistHousingType(complex, "26B", "26.70");
        HousingType typeC = persistHousingType(complex, "26C", "26.90");
        HousingType other = persistHousingType(complex, "44", "44.87");
        Announcement active = persistAnnouncement(null, "ORIGINAL", "subtypes", TODAY, TODAY, TODAY.plusDays(2));
        SupplySheetSeed seed = new SupplySheetSeed(entityManager);
        SupplyRow rowA = persistSupplyRow(active, complex, typeA, "joint-row-a", 1);
        SupplyRow rowB = persistSupplyRow(active, complex, typeB, "joint-row-b", 1);
        SupplyRow rowC = persistSupplyRow(active, complex, typeC, "joint-row-c", 1);
        SupplyRowTarget cellA = seed.cell(rowA, "청년", "신청 조건", 1);
        SupplyRowTarget cellB = seed.cell(rowB, "청년", "신청 조건", 1);
        SupplyRowTarget cellC = seed.cell(rowC, "청년", "신청 조건", 1);
        seed.lease(new LeaseTerms.Amounts(new BigDecimal("10"), new BigDecimal("100"), null), cellA, cellB, cellC);
        SupplyRow otherRow = persistSupplyRow(active, complex, other, "other-row", 2);
        SupplyRowTarget otherTarget = persistSupplyTarget(otherRow, "신혼부부", "20", "200", null, 1);
        entityManager.flush();

        assertEquals(List.of(
                condition(active, rowA, typeA, cellA, "10", "100", null),
                condition(active, rowB, typeB, cellB, "10", "100", null),
                condition(active, rowC, typeC, cellC, "10", "100", null),
                condition(active, otherRow, other, otherTarget, "20", "200", null)
        ), read(complex).conditions());
    }

    @Test
    void 확정_전_공고는_준비_줄로_공급조건과_대상을_보인다() {
        HousingComplex complex = persistComplex("준비 단지");
        HousingType type = persistHousingType(complex, "36A", "36.12");
        Announcement staged = persistAnnouncement(null, "ORIGINAL", "staged", TODAY, TODAY, TODAY.plusDays(2));
        AnnouncementComplex announcementComplex = AnnouncementComplex.observe(staged, "준비 단지", null, null);
        entityManager.persist(announcementComplex);
        announcementComplex.linkBySource(complex, ComplexLinkMethod.AUTO,
                "test", Instant.parse("2026-01-01T00:00:00Z"));
        line(staged, announcementComplex, "LH:1", 0, "36A(청년)", "36.12", 5, "1000", "10");
        line(staged, announcementComplex, "LH:2", 1, "36A(고령자)", "36.12", 3, "900", "9");
        entityManager.flush();

        ComplexCurrentSupplies.Result result = read(complex);

        assertThat(result.conditions()).extracting(CurrentSupplyConditionRow::housingTypeId,
                        CurrentSupplyConditionRow::target, row -> row.deposit().stripTrailingZeros().toPlainString())
                .containsExactly(tuple(type.getId(), "청년", "1000"),
                        tuple(type.getId(), "고령자", "900"));
        assertThat(result.targets()).extracting(CurrentAnnouncementTargetRow::target).containsExactly("청년", "고령자");
        assertThat(staged.isSupplyConfirmed()).isFalse();
    }

    /** 저장한 값을 DB에서 다시 읽는다(금액 scale 등 운영 조회와 같은 값). */
    private ComplexCurrentSupplies.Result read(HousingComplex complex) {
        entityManager.flush();
        entityManager.clear();
        return currentSupplies.read(complex.getId(), repository.findCurrentAnnouncements(complex.getId(), TODAY));
    }

    private void line(Announcement announcement, AnnouncementComplex complex, String key, int order, String typeName,
            String area, int count, String deposit, String rent) {
        entityManager.persist(AgencySupplyLine.create(announcement, AgencyCode.LH, new AgencySupplyLine.Values(key, order,
                complex, complex.getSourceComplexName(), typeName, new BigDecimal(area), null, null, count,
                new BigDecimal(deposit), new BigDecimal(rent), null, SupplyCategory.NEW_SUPPLY)));
    }

    private CurrentSupplyConditionRow condition(Announcement announcement, SupplyRow supplyRow,
            HousingType housingType, SupplyRowTarget target, String deposit, String monthlyRent,
            String convertibleDeposit) {
        return new CurrentSupplyConditionRow(announcement.getId(), supplyRow.getId(), housingType.getId(),
                target.getId(), target.getTarget().getLabel(), new BigDecimal(deposit + ".00"),
                new BigDecimal(monthlyRent + ".00"),
                convertibleDeposit == null ? null : new BigDecimal(convertibleDeposit + ".00"),
                announcement.getPostedDate());
    }

    private HousingComplex persistComplex(String name) {
        HousingComplex complex = HousingComplex.create(name, "source-" + name, "행복주택",
                Address.create("서울특별시 중구 세종대로 110", "1114010100100010000", "1114010100", "11", "11140",
                        new BigDecimal("37.500000"), new BigDecimal("126.900000")),
                100, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, 7);
        entityManager.persist(complex);
        return complex;
    }

    private HousingType persistHousingType(HousingComplex complex, String name, String exclusiveArea) {
        HousingType housingType = HousingType.create(complex, name, new BigDecimal(exclusiveArea), null, 50, "floor-" + name,
                false, null);
        entityManager.persist(housingType);
        return housingType;
    }

    private Announcement persistAnnouncement(Announcement previous, String status, String suffix,
            LocalDate postedDate, LocalDate applicationStartDate, LocalDate applicationEndDate) {
        Announcement announcement = Announcement.create("source-" + suffix,
                previous == null ? null : previous.getSourceAnnouncementIdentifier(), previous, "공고 " + suffix,
                status, "행복주택", "신규모집", "LH", postedDate, applicationStartDate, applicationEndDate,
                applicationEndDate.plusMonths(1), "https://example.com/announcements/" + suffix, null, 0, null, null,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)));
        entityManager.persist(announcement);
        return announcement;
    }

    private SupplyRow persistSupplyRow(Announcement announcement, HousingComplex complex, HousingType housingType,
            String sourceIdentifier, int displayOrder) {
        SupplyRow supplyRow = SupplyRowSeed.create(entityManager, announcement, complex,
                housingType, displayOrder, complex.getName(),
                housingType == null ? "36A" : housingType.getName(), "1114010100100010000", YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY);
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private SupplyRow persistUnmatchedSupplyRow(Announcement announcement, String sourceIdentifier) {
        SupplyRow supplyRow = SupplyRowSeed.create(entityManager, announcement, null, null, 1, "매칭되지 않은 단지", "36A", "1114010100100010000", YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY);
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private SupplyRowTarget persistSupplyTarget(SupplyRow supplyRow, String target, String deposit,
            String monthlyRent, String convertibleDeposit, int displayOrder) {
        return new SupplySheetSeed(entityManager).target(supplyRow, target, "신청 조건", 5, 5, new BigDecimal(deposit),
                new BigDecimal(monthlyRent), convertibleDeposit == null ? null : new BigDecimal(convertibleDeposit),
                displayOrder);
    }
}
