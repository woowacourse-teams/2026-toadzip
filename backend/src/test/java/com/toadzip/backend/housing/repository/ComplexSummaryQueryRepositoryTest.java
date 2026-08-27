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
import com.toadzip.backend.housing.domain.MapBounds;
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
    void 경계_안과_남서_북동_경계선의_단지만_ID_오름차순으로_조회한다() {
        HousingComplex boundaryComplex = persistComplex("경계 단지", "37.400000", "126.800000");
        HousingComplex insideComplex = persistComplex("영역 안 단지", "37.500000", "126.900000");
        HousingComplex northEastBoundaryComplex = persistComplex("북동 경계 단지", "37.600000", "127.100000");
        persistComplex("영역 밖 단지", "37.700000", "126.900000");
        entityManager.flush();

        List<Long> complexIds = repository.findAllInBounds(SEOUL_BOUNDS).stream()
                .map(ComplexSummaryRow::complexId)
                .toList();

        assertEquals(
                List.of(boundaryComplex.getId(), insideComplex.getId(), northEastBoundaryComplex.getId()),
                complexIds
        );
    }

    @Test
    void 대표_공고의_모든_공급행에서만_가격_범위를_집계한다() {
        HousingComplex complex = persistComplex("가격 집계 단지", "37.500000", "126.900000");
        HousingType housingType = persistHousingType(complex, "36A", "36.12");
        HousingType secondHousingType = persistHousingType(complex, "44B", "44.87");
        Announcement oldAnnouncement = persistAnnouncement(null, "ORIGINAL", LocalDate.of(2026, 7, 1), "old");
        SupplyRow oldSupplyRow = persistSupplyRow(oldAnnouncement, complex, housingType, "old-row", 1);
        persistSupplyTarget(oldSupplyRow, "과거", "10000000", "100000", 1);
        Announcement representative = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "representative"
        );
        SupplyRow firstRepresentativeRow = persistSupplyRow(
                representative,
                complex,
                housingType,
                "latest-first-row",
                1
        );
        SupplyRow secondRepresentativeRow = persistSupplyRow(
                representative,
                complex,
                secondHousingType,
                "latest-second-row",
                2
        );
        persistSupplyTarget(firstRepresentativeRow, "청년", "50000000", "300000", 1);
        persistSupplyTarget(secondRepresentativeRow, "신혼부부", "70000000", "200000", 1);
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
    void 대표_공고의_공급행에_공급대상이_없으면_가격_범위를_null로_조회한다() {
        HousingComplex complex = persistComplex("공급대상 없는 단지", "37.500000", "126.900000");
        HousingType housingType = persistHousingType(complex, "36A", "36.00");
        Announcement representative = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "without-target"
        );
        persistSupplyRow(representative, complex, housingType, "without-target-row", 1);
        entityManager.flush();

        ComplexSummaryRow row = repository.findAllInBounds(SEOUL_BOUNDS).getFirst();

        assertAll(
                () -> assertEquals(representative.getId(), row.announcementId()),
                () -> assertNull(row.depositMin()),
                () -> assertNull(row.depositMax()),
                () -> assertNull(row.monthlyRentMin()),
                () -> assertNull(row.monthlyRentMax())
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

    @Test
    void 최신_대표공고_게시일과_단지_ID로_안정적으로_페이지를_나눈다() {
        HousingComplex noAnnouncement = persistComplex("공고 없는 단지", "37.500000", "126.900000");
        HousingComplex cancelled = persistComplex("취소 단지", "37.500000", "126.900000");
        HousingComplex older = persistComplex("오래된 단지", "37.500000", "126.900000");
        HousingComplex sameDateSmaller = persistComplex("동일일 작은 ID", "37.500000", "126.900000");
        HousingComplex cursorComplex = persistComplex("커서 단지", "37.500000", "126.900000");
        HousingComplex corrected = persistComplex("정정 단지", "37.500000", "126.900000");
        HousingComplex nullDateGreater = persistComplex("공고 없는 큰 ID", "37.500000", "126.900000");

        persistRepresentative(older, "ORIGINAL", LocalDate.of(2026, 8, 8), "older");
        persistRepresentative(sameDateSmaller, "ORIGINAL", LocalDate.of(2026, 8, 9), "same-smaller");
        persistRepresentative(cursorComplex, "ORIGINAL", LocalDate.of(2026, 8, 9), "cursor");

        Announcement cancelledOriginal = persistRepresentative(
                cancelled,
                "ORIGINAL",
                LocalDate.of(2026, 8, 7),
                "cancelled-original"
        );
        Announcement cancellation = persistAnnouncement(
                cancelledOriginal,
                "CANCELLATION",
                LocalDate.of(2026, 8, 10),
                "cancellation-leaf"
        );
        persistSupplyRow(cancellation, cancelled, null, "cancellation-row", 1);

        Announcement correctedOriginal = persistRepresentative(
                corrected,
                "ORIGINAL",
                LocalDate.of(2026, 8, 6),
                "corrected-original"
        );
        Announcement correction = persistAnnouncement(
                correctedOriginal,
                "CORRECTION",
                LocalDate.of(2026, 8, 10),
                "correction-leaf"
        );
        persistSupplyRow(correction, corrected, null, "correction-row", 1);

        Announcement unmatched = persistAnnouncement(null, "ORIGINAL", LocalDate.of(2026, 8, 12), "unmatched");
        persistUnmatchedSupplyRow(unmatched);
        entityManager.flush();

        List<ComplexSummaryRow> first = repository.findFirstPage(SEOUL_BOUNDS, 3);
        assertEquals(
                List.of(corrected.getId(), cursorComplex.getId(), sameDateSmaller.getId()),
                ids(first)
        );
        ComplexSummaryCursor cursor = cursorOf(first.get(1));
        List<ComplexSummaryRow> second = repository.findPageAfter(SEOUL_BOUNDS, cursor, 10);

        assertAll(
                () -> assertEquals(correction.getId(), first.getFirst().announcementId()),
                () -> assertEquals("CORRECTION", first.getFirst().publicationType()),
                () -> assertEquals(
                        List.of(
                                sameDateSmaller.getId(),
                                older.getId(),
                                nullDateGreater.getId(),
                                cancelled.getId(),
                                noAnnouncement.getId()
                        ),
                        ids(second)
                ),
                () -> assertNull(second.get(3).announcementId()),
                () -> assertNull(second.get(4).announcementId())
        );
    }

    @Test
    void 게시일이_null인_커서_뒤에는_더_작은_ID의_null_게시일_단지만_포함한다() {
        HousingComplex smaller = persistComplex("작은 ID", "37.500000", "126.900000");
        HousingComplex cursorComplex = persistComplex("null 커서 단지", "37.500000", "126.900000");
        HousingComplex greater = persistComplex("큰 ID", "37.500000", "126.900000");
        HousingComplex announced = persistComplex("공고 단지", "37.500000", "126.900000");
        persistRepresentative(announced, "ORIGINAL", LocalDate.of(2026, 8, 1), "announced");
        entityManager.flush();

        List<ComplexSummaryRow> page = repository.findPageAfter(
                SEOUL_BOUNDS,
                new ComplexSummaryCursor(null, cursorComplex.getId()),
                10
        );

        assertEquals(List.of(smaller.getId()), ids(page));
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

    private Announcement persistRepresentative(
            HousingComplex complex,
            String status,
            LocalDate postedDate,
            String suffix
    ) {
        Announcement announcement = persistAnnouncement(null, status, postedDate, suffix);
        persistSupplyRow(announcement, complex, null, suffix + "-row", 1);
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

    private String sourceHousingTypeName(HousingType housingType) {
        if (housingType == null) {
            return "36A";
        }
        return housingType.getName();
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

    private void persistUnmatchedSupplyRow(Announcement announcement) {
        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                null,
                null,
                "unmatched-row",
                1,
                "매칭되지 않은 단지",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                "단지 매칭 실패",
                10
        );
        entityManager.persist(supplyRow);
    }

    private ComplexSummaryCursor cursorOf(ComplexSummaryRow row) {
        return new ComplexSummaryCursor(row.postedDate(), row.complexId());
    }

    private List<Long> ids(List<ComplexSummaryRow> rows) {
        return rows.stream().map(ComplexSummaryRow::complexId).toList();
    }

    private void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
