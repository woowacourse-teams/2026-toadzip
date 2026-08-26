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
import com.toadzip.backend.housing.service.MapBounds;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(ComplexSummaryQueryRepository.class)
class ComplexSummaryQueryRepositoryTest {

    private static final MapBounds SEOUL_BOUNDS = MapBounds.of(
            new BigDecimal("37.400000"),
            new BigDecimal("126.800000"),
            new BigDecimal("37.600000"),
            new BigDecimal("127.100000")
    );

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ComplexSummaryQueryRepository repository;

    @Test
    void 경계_안과_경계선의_단지만_ID_오름차순으로_조회한다() {
        HousingComplex boundaryComplex = persistComplex("경계 단지", "37.400000", "126.800000");
        HousingComplex insideComplex = persistComplex("영역 안 단지", "37.500000", "126.900000");
        persistComplex("영역 밖 단지", "37.700000", "126.900000");
        entityManager.flush();

        List<Long> complexIds = repository.findAllInBounds(SEOUL_BOUNDS).stream()
                .map(ComplexSummaryRow::complexId)
                .toList();

        assertEquals(List.of(boundaryComplex.getId(), insideComplex.getId()), complexIds);
    }

    @Test
    void 대표_공고의_공급대상에서만_가격_범위를_집계한다() {
        HousingComplex complex = persistComplex("가격 집계 단지", "37.500000", "126.900000");
        HousingType housingType = persistHousingType(complex, "36A", "36.12");
        persistHousingType(complex, "44B", "44.87");
        Announcement oldAnnouncement = persistAnnouncement(null, "ORIGINAL", LocalDate.of(2026, 7, 1), "old");
        SupplyRow oldSupplyRow = persistSupplyRow(oldAnnouncement, complex, housingType, "old-row", 1);
        persistSupplyTarget(oldSupplyRow, "과거", "10000000", "100000", 1);
        Announcement representative = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "representative"
        );
        SupplyRow representativeRow = persistSupplyRow(representative, complex, housingType, "latest-row", 1);
        persistSupplyTarget(representativeRow, "청년", "50000000", "200000", 1);
        persistSupplyTarget(representativeRow, "신혼부부", "70000000", "300000", 2);
        entityManager.flush();

        ComplexSummaryRow row = repository.findAllInBounds(SEOUL_BOUNDS).getFirst();

        assertAll(
                () -> assertEquals(new BigDecimal("36.12"), row.exclusiveAreaMin()),
                () -> assertEquals(new BigDecimal("44.87"), row.exclusiveAreaMax()),
                () -> assertBigDecimalEquals("50000000", row.depositMin()),
                () -> assertBigDecimalEquals("70000000", row.depositMax()),
                () -> assertBigDecimalEquals("200000", row.monthlyRentMin()),
                () -> assertBigDecimalEquals("300000", row.monthlyRentMax()),
                () -> assertEquals(representative.getId(), row.announcementId())
        );
    }

    @Test
    void 취소_후속_공고가_있으면_취소된_이전_공고를_대표로_되살리지_않는다() {
        HousingComplex complex = persistComplex("취소 단지", "37.500000", "126.900000");
        HousingType housingType = persistHousingType(complex, "36A", "36.00");
        Announcement original = persistAnnouncement(null, "ORIGINAL", LocalDate.of(2026, 7, 1), "original");
        Announcement cancellation = persistAnnouncement(
                original,
                "CANCELLATION",
                LocalDate.of(2026, 7, 2),
                "cancellation"
        );
        SupplyRow originalRow = persistSupplyRow(original, complex, housingType, "original-row", 1);
        SupplyRow cancellationRow = persistSupplyRow(cancellation, complex, housingType, "cancellation-row", 1);
        persistSupplyTarget(originalRow, "원공고", "50000000", "200000", 1);
        persistSupplyTarget(cancellationRow, "취소공고", "70000000", "300000", 1);
        entityManager.flush();

        ComplexSummaryRow row = repository.findAllInBounds(SEOUL_BOUNDS).getFirst();

        assertAll(
                () -> assertNull(row.announcementId()),
                () -> assertNull(row.publicationType()),
                () -> assertNull(row.depositMin()),
                () -> assertNull(row.depositMax()),
                () -> assertNull(row.monthlyRentMin()),
                () -> assertNull(row.monthlyRentMax())
        );
    }

    private HousingComplex persistComplex(String name, String latitude, String longitude) {
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
                        new BigDecimal(latitude),
                        new BigDecimal(longitude)
                ),
                100,
                "LH",
                LocalDate.of(2020, 1, 1),
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                null,
                null
        );
        entityManager.persist(complex);
        return complex;
    }

    private HousingType persistHousingType(
            HousingComplex complex,
            String name,
            String exclusiveArea
    ) {
        HousingType housingType = HousingType.create(
                complex,
                name,
                new BigDecimal(exclusiveArea),
                null,
                50,
                "https://example.com/floor-plan.png",
                false,
                null
        );
        entityManager.persist(housingType);
        return housingType;
    }

    private Announcement persistAnnouncement(
            Announcement previousAnnouncement,
            String status,
            LocalDate postedDate,
            String suffix
    ) {
        String previousSourceIdentifier = null;
        if (previousAnnouncement != null) {
            previousSourceIdentifier = "source-original";
        }
        Announcement announcement = Announcement.create(
                "source-" + suffix,
                previousSourceIdentifier,
                previousAnnouncement,
                "공고 " + suffix,
                status,
                "행복주택",
                "신규모집",
                "LH",
                postedDate,
                postedDate.plusDays(10),
                postedDate.plusDays(14),
                postedDate.plusMonths(1),
                "https://example.com/announcements/" + suffix,
                null,
                0,
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
                housingType.getName(),
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                10
        );
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private void persistSupplyTarget(
            SupplyRow supplyRow,
            String target,
            String rentalDeposit,
            String monthlyRent,
            int displayOrder
    ) {
        entityManager.persist(SupplyTarget.create(
                supplyRow,
                target,
                "1순위",
                5,
                5,
                new BigDecimal(rentalDeposit),
                new BigDecimal(monthlyRent),
                null,
                "신청 조건",
                displayOrder
        ));
    }

    private void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
