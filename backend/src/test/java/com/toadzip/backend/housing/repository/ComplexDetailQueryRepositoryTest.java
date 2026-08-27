package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(ComplexDetailQueryRepository.class)
class ComplexDetailQueryRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ComplexDetailQueryRepository repository;

    @Test
    void 단지_기본정보와_좌표를_조회하고_없는_ID는_빈_결과를_반환한다() {
        HousingComplex complex = persistComplex("상세 단지", "https://example.com/complex.png");
        entityManager.flush();

        Optional<ComplexDetailRow> actual = repository.findComplex(complex.getId());

        assertAll(
                () -> assertEquals(Optional.of(expectedComplex(complex.getId())), actual),
                () -> assertEquals(Optional.empty(), repository.findComplex(Long.MAX_VALUE))
        );
    }

    @Test
    void 단지의_주택형을_ID_오름차순으로_조회한다() {
        HousingComplex complex = persistComplex("주택형 단지", null);
        HousingType first = persistHousingType(
                complex,
                "36A",
                "36.12",
                null,
                "https://example.com/36a.png",
                false,
                null
        );
        HousingType second = persistHousingType(
                complex,
                "44B",
                "44.87",
                "51.10",
                "https://example.com/44b.png",
                true,
                "123456"
        );
        entityManager.flush();

        List<HousingTypeDetailRow> rows = repository.findHousingTypes(complex.getId());

        assertEquals(List.of(
                new HousingTypeDetailRow(
                        first.getId(),
                        "36A",
                        new BigDecimal("36.12"),
                        null,
                        "https://example.com/36a.png",
                        false,
                        null
                ),
                new HousingTypeDetailRow(
                        second.getId(),
                        "44B",
                        new BigDecimal("44.87"),
                        new BigDecimal("51.10"),
                        "https://example.com/44b.png",
                        true,
                        new BigDecimal("123456.00")
                )
        ), rows);
    }

    @Test
    void 최신_leaf이면서_접수전_또는_접수중이고_단지에_매칭된_공고만_조회한다() {
        HousingComplex complex = persistComplex("현재 공고 단지", null);
        HousingType housingType = persistHousingType(complex, "36A", "36.12", null, "floor", false, null);

        Announcement applying = persistAnnouncement(
                null, "ORIGINAL", "applying", TODAY.minusDays(3), TODAY.minusDays(2), TODAY
        );
        persistSupplyRow(applying, complex, housingType, "applying-row", 1);
        Announcement beforeApplication = persistAnnouncement(
                null, "ORIGINAL", "before", TODAY.minusDays(4), TODAY.plusDays(1), TODAY.plusDays(5)
        );
        persistSupplyRow(beforeApplication, complex, housingType, "before-row", 1);
        Announcement ended = persistAnnouncement(
                null, "ORIGINAL", "ended", TODAY.minusDays(1), TODAY.minusDays(3), TODAY.minusDays(1)
        );
        persistSupplyRow(ended, complex, housingType, "ended-row", 1);

        Announcement correctionOriginal = persistAnnouncement(
                null, "ORIGINAL", "correction-original", TODAY.minusDays(10), TODAY.minusDays(2), TODAY.plusDays(2)
        );
        persistSupplyRow(correctionOriginal, complex, housingType, "correction-original-row", 1);
        Announcement correction = persistAnnouncement(
                correctionOriginal,
                "CORRECTION",
                "correction",
                TODAY,
                TODAY,
                TODAY.plusDays(3),
                new BigDecimal("2.5000")
        );
        persistSupplyRow(correction, complex, housingType, "correction-row", 1);

        Announcement cancelledOriginal = persistAnnouncement(
                null, "ORIGINAL", "cancelled-original", TODAY.minusDays(9), TODAY, TODAY.plusDays(2)
        );
        persistSupplyRow(cancelledOriginal, complex, housingType, "cancelled-original-row", 1);
        Announcement cancellation = persistAnnouncement(
                cancelledOriginal,
                "CANCELLATION",
                "cancellation",
                TODAY.plusDays(1),
                TODAY,
                TODAY.plusDays(2)
        );
        persistSupplyRow(cancellation, complex, housingType, "cancellation-row", 1);

        Announcement unmatched = persistAnnouncement(
                null, "ORIGINAL", "unmatched", TODAY.plusDays(2), TODAY, TODAY.plusDays(2)
        );
        persistUnmatchedSupplyRow(unmatched, "unmatched-row");
        entityManager.flush();

        List<CurrentAnnouncementRow> rows = repository.findCurrentAnnouncements(complex.getId(), TODAY);

        assertAll(
                () -> assertEquals(
                        List.of(correction.getId(), applying.getId(), beforeApplication.getId()),
                        announcementIds(rows)
                ),
                () -> assertEquals(List.of("CORRECTION", "ORIGINAL", "ORIGINAL"),
                        rows.stream().map(CurrentAnnouncementRow::publicationType).toList()),
                () -> assertEquals(new BigDecimal("2.5000"), rows.getFirst().actualCompetitionRate()),
                () -> assertNull(rows.get(1).actualCompetitionRate())
        );
    }

    @Test
    void 현재공고_대상은_공고별_distinct와_표시순서를_보장한다() {
        HousingComplex complex = persistComplex("공고 대상 단지", null);
        HousingType housingType = persistHousingType(complex, "36A", "36.12", null, "floor", false, null);
        Announcement newer = persistAnnouncement(
                null, "ORIGINAL", "newer", TODAY, TODAY, TODAY.plusDays(2)
        );
        SupplyRow laterRow = persistSupplyRow(newer, complex, housingType, "later-row", 2);
        persistSupplyTarget(laterRow, "청년", "3", "300", null, 1);
        SupplyRow firstRow = persistSupplyRow(newer, complex, housingType, "first-row", 1);
        SupplyTarget first = persistSupplyTarget(firstRow, "신혼부부", "1", "100", null, 2);
        SupplyTarget second = persistSupplyTarget(firstRow, "청년", "2", "200", null, 3);
        Announcement older = persistAnnouncement(
                null, "ORIGINAL", "older", TODAY.minusDays(1), TODAY, TODAY.plusDays(1)
        );
        SupplyRow olderRow = persistSupplyRow(older, complex, housingType, "older-row", 1);
        SupplyTarget olderTarget = persistSupplyTarget(olderRow, "고령자", "4", "400", null, 1);
        entityManager.flush();

        List<CurrentAnnouncementTargetRow> rows = repository.findCurrentAnnouncementTargets(
                complex.getId(),
                TODAY
        );

        assertEquals(List.of(
                new CurrentAnnouncementTargetRow(newer.getId(), firstRow.getId(), first.getId(), "신혼부부"),
                new CurrentAnnouncementTargetRow(newer.getId(), firstRow.getId(), second.getId(), "청년"),
                new CurrentAnnouncementTargetRow(older.getId(), olderRow.getId(), olderTarget.getId(), "고령자")
        ), rows);
    }

    @Test
    void 현재공급조건은_미매칭_종료_취소를_제외하고_순서를_보장한다() {
        HousingComplex complex = persistComplex("공급 조건 단지", null);
        HousingType firstType = persistHousingType(complex, "36A", "36.12", null, "floor-a", false, null);
        HousingType secondType = persistHousingType(complex, "44B", "44.87", null, "floor-b", false, null);
        Announcement active = persistAnnouncement(
                null, "ORIGINAL", "active", TODAY, TODAY, TODAY.plusDays(2)
        );
        SupplyRow laterRow = persistSupplyRow(active, complex, secondType, "later-row", 2);
        SupplyTarget laterTarget = persistSupplyTarget(laterRow, "신혼부부", "30", "300", "3000", 1);
        SupplyRow firstRow = persistSupplyRow(active, complex, firstType, "first-row", 1);
        SupplyTarget secondTarget = persistSupplyTarget(firstRow, "청년", "20", "200", null, 2);
        SupplyTarget firstTarget = persistSupplyTarget(firstRow, "대학생", "10", "100", "1000", 1);

        SupplyRow missingType = persistSupplyRow(active, complex, null, "missing-type", 3);
        persistSupplyTarget(missingType, "주택형 미매칭", "40", "400", null, 1);
        SupplyRow missingComplex = persistUnmatchedSupplyRow(active, "missing-complex");
        persistSupplyTarget(missingComplex, "단지 미매칭", "50", "500", null, 1);

        Announcement ended = persistAnnouncement(
                null, "ORIGINAL", "ended", TODAY.minusDays(1), TODAY.minusDays(3), TODAY.minusDays(1)
        );
        SupplyRow endedRow = persistSupplyRow(ended, complex, firstType, "ended-row", 1);
        persistSupplyTarget(endedRow, "종료", "60", "600", null, 1);

        Announcement original = persistAnnouncement(
                null, "ORIGINAL", "original", TODAY.minusDays(2), TODAY, TODAY.plusDays(3)
        );
        SupplyRow originalRow = persistSupplyRow(original, complex, firstType, "original-row", 1);
        persistSupplyTarget(originalRow, "원공고", "70", "700", null, 1);
        Announcement cancellation = persistAnnouncement(
                original, "취소공고", "cancellation", TODAY.plusDays(1), TODAY, TODAY.plusDays(3)
        );
        SupplyRow cancellationRow = persistSupplyRow(cancellation, complex, firstType, "cancellation-row", 1);
        persistSupplyTarget(cancellationRow, "취소공고", "80", "800", null, 1);
        entityManager.flush();

        List<CurrentSupplyConditionRow> rows = repository.findCurrentSupplyConditions(complex.getId(), TODAY);

        assertEquals(List.of(
                condition(active, firstRow, firstType, firstTarget, "10", "100", "1000"),
                condition(active, firstRow, firstType, secondTarget, "20", "200", null),
                condition(active, laterRow, secondType, laterTarget, "30", "300", "3000")
        ), rows);
    }

    private ComplexDetailRow expectedComplex(long complexId) {
        return new ComplexDetailRow(
                complexId,
                "상세 단지",
                "https://example.com/complex.png",
                "11",
                "11140",
                "서울특별시 중구 세종대로 110",
                "행복주택",
                "LH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                LocalDate.of(2020, 1, 1),
                "아파트",
                true,
                "개별난방",
                "계단식",
                7,
                100,
                80
        );
    }

    private CurrentSupplyConditionRow condition(
            Announcement announcement,
            SupplyRow supplyRow,
            HousingType housingType,
            SupplyTarget target,
            String deposit,
            String monthlyRent,
            String convertibleDeposit
    ) {
        BigDecimal converted = null;
        if (convertibleDeposit != null) {
            converted = new BigDecimal(convertibleDeposit + ".00");
        }
        return new CurrentSupplyConditionRow(
                announcement.getId(),
                supplyRow.getId(),
                housingType.getId(),
                target.getId(),
                target.getTarget(),
                new BigDecimal(deposit + ".00"),
                new BigDecimal(monthlyRent + ".00"),
                converted
        );
    }

    private HousingComplex persistComplex(String name, String imageUrl) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal("37.500000"),
                        new BigDecimal("126.900000")
                ),
                100,
                "LH",
                LocalDate.of(2020, 1, 1),
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                imageUrl,
                7
        );
        entityManager.persist(complex);
        return complex;
    }

    private HousingType persistHousingType(
            HousingComplex complex,
            String name,
            String exclusiveArea,
            String supplyArea,
            String floorPlanUrl,
            boolean duplex,
            String maintenanceFee
    ) {
        BigDecimal storedSupplyArea = null;
        if (supplyArea != null) {
            storedSupplyArea = new BigDecimal(supplyArea);
        }
        BigDecimal storedMaintenanceFee = null;
        if (maintenanceFee != null) {
            storedMaintenanceFee = new BigDecimal(maintenanceFee);
        }
        HousingType housingType = HousingType.create(
                complex,
                name,
                new BigDecimal(exclusiveArea),
                storedSupplyArea,
                50,
                floorPlanUrl,
                duplex,
                storedMaintenanceFee
        );
        entityManager.persist(housingType);
        return housingType;
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String suffix,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate
    ) {
        return persistAnnouncement(
                previous,
                status,
                suffix,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                null
        );
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String suffix,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            BigDecimal actualCompetitionRate
    ) {
        String previousSourceIdentifier = null;
        if (previous != null) {
            previousSourceIdentifier = previous.getSourceAnnouncementIdentifier();
        }
        Announcement announcement = Announcement.create(
                "source-" + suffix,
                previousSourceIdentifier,
                previous,
                "공고 " + suffix,
                status,
                "행복주택",
                "신규모집",
                "LH",
                postedDate,
                applicationStartDate,
                applicationEndDate,
                applicationEndDate.plusMonths(1),
                "https://example.com/announcements/" + suffix,
                null,
                0,
                actualCompetitionRate,
                null,
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)
        );
        entityManager.persist(announcement);
        return announcement;
    }

    private SupplyRow persistSupplyRow(
            Announcement announcement,
            HousingComplex complex,
            HousingType housingType,
            String sourceIdentifier,
            int displayOrder
    ) {
        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                complex,
                housingType,
                sourceIdentifier,
                displayOrder,
                complex.getName(),
                sourceHousingTypeName(housingType),
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                10
        );
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private SupplyRow persistUnmatchedSupplyRow(Announcement announcement, String sourceIdentifier) {
        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                null,
                null,
                sourceIdentifier,
                1,
                "매칭되지 않은 단지",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                "매칭 실패",
                10
        );
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private SupplyTarget persistSupplyTarget(
            SupplyRow supplyRow,
            String target,
            String deposit,
            String monthlyRent,
            String convertibleDeposit,
            int displayOrder
    ) {
        BigDecimal converted = null;
        if (convertibleDeposit != null) {
            converted = new BigDecimal(convertibleDeposit);
        }
        SupplyTarget supplyTarget = SupplyTarget.create(
                supplyRow,
                target,
                "1순위",
                5,
                5,
                new BigDecimal(deposit),
                new BigDecimal(monthlyRent),
                converted,
                "신청 조건",
                displayOrder
        );
        entityManager.persist(supplyTarget);
        return supplyTarget;
    }

    private String sourceHousingTypeName(HousingType housingType) {
        if (housingType == null) {
            return "36A";
        }
        return housingType.getName();
    }

    private List<Long> announcementIds(List<CurrentAnnouncementRow> rows) {
        return rows.stream().map(CurrentAnnouncementRow::announcementId).toList();
    }
}
