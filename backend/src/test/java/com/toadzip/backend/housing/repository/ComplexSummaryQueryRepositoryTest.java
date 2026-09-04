package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        ComplexSummaryQueryRepository.class,
        ComplexSummarySqlBuilder.class,
        HousingComplexFilterPredicateBuilder.class
})
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

        List<Long> complexIds = repository.findAll(noFilters(SEOUL_BOUNDS)).stream()
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

        ComplexSummaryRow row = repository.findAll(noFilters(SEOUL_BOUNDS)).getFirst();

        assertAll(
                () -> assertEquals(new BigDecimal("36.1200"), row.exclusiveAreaMin()),
                () -> assertEquals(new BigDecimal("44.8700"), row.exclusiveAreaMax()),
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

        ComplexSummaryRow row = repository.findAll(noFilters(SEOUL_BOUNDS)).getFirst();

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

        ComplexSummaryRow row = repository.findAll(noFilters(SEOUL_BOUNDS)).getFirst();

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
    void 취소_상태로_검색하면_최신_취소_공고가_연결된_단지만_조회한다() {
        HousingComplex cancelled = persistComplex("취소 단지", "37.500000", "126.900000");
        HousingComplex active = persistComplex("정상 단지", "37.510000", "126.910000");
        Announcement original = persistRepresentative(
                cancelled, "ORIGINAL", LocalDate.of(2026, 8, 1), "cancel-filter-original"
        );
        Announcement cancellation = persistAnnouncement(
                original, "CANCELLATION", LocalDate.of(2026, 8, 2), "cancel-filter-leaf"
        );
        persistSupplyRow(cancellation, cancelled, null, "cancel-filter-row", 1);
        persistRepresentative(active, "ORIGINAL", LocalDate.of(2026, 8, 1), "active-filter");
        entityManager.flush();

        assertBothQueryPaths(
                integratedSearchCondition(Set.of(ApplicationStatus.CANCELLED), null),
                cancelled.getId()
        );
    }

    @Test
    void 모집_시작일과_종료일은_모집중에_포함한다() {
        ActiveAnnouncementFixture fixture = persistActiveAnnouncementFixture();
        entityManager.flush();

        assertBothQueryPaths(integratedSearchCondition(Set.of(), true), fixture.activeComplexIds());
    }

    @Test
    void 모집중_공고가_없는_단지는_지도와_목록에서_동일하게_조회한다() {
        ActiveAnnouncementFixture fixture = persistActiveAnnouncementFixture();
        entityManager.flush();

        assertBothQueryPaths(
                integratedSearchCondition(Set.of(), false),
                fixture.inactiveComplexIds()
        );
    }

    @Test
    void 모집중_공고_조건이_null이면_지도와_목록_모두_제한하지_않는다() {
        ActiveAnnouncementFixture fixture = persistActiveAnnouncementFixture();
        entityManager.flush();

        assertBothQueryPaths(
                integratedSearchCondition(Set.of(), null),
                fixture.allComplexIds()
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

        List<ComplexSummaryRow> first = repository.findPage(
                noFilters(SEOUL_BOUNDS),
                ComplexSort.LATEST_ANNOUNCEMENT,
                null,
                3
        );
        assertEquals(
                List.of(corrected.getId(), cursorComplex.getId(), sameDateSmaller.getId()),
                ids(first)
        );
        ComplexSummaryCursor cursor = cursorOf(first.get(1));
        List<ComplexSummaryRow> second = repository.findPage(
                noFilters(SEOUL_BOUNDS),
                ComplexSort.LATEST_ANNOUNCEMENT,
                cursor,
                10
        );

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

        List<ComplexSummaryRow> page = repository.findPage(
                noFilters(SEOUL_BOUNDS),
                ComplexSort.LATEST_ANNOUNCEMENT,
                new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, cursorComplex.getId()),
                10
        );

        assertEquals(List.of(smaller.getId()), ids(page));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComplexSort.class)
    void 다섯_정렬은_null을_마지막에_두고_동률이면_단지_ID_내림차순으로_조회한다(
            ComplexSort sort
    ) {
        List<Long> expectedIds = persistSortOrderFixture(sort);
        entityManager.flush();

        List<Long> actualIds = ids(repository.findPage(noFilters(SEOUL_BOUNDS), sort, null, 10));

        assertEquals(expectedIds, actualIds);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComplexSort.class)
    void 다섯_정렬의_keyset_두_페이지에는_중복과_누락이_없다(ComplexSort sort) {
        List<Long> expectedIds = persistSortOrderFixture(sort);
        entityManager.flush();

        List<ComplexSummaryRow> first = repository.findPage(noFilters(SEOUL_BOUNDS), sort, null, 2);
        List<ComplexSummaryRow> second = repository.findPage(
                noFilters(SEOUL_BOUNDS),
                sort,
                cursorOf(first.getLast(), sort),
                10
        );

        List<Long> firstIds = ids(first);
        List<Long> secondIds = ids(second);
        Set<Long> intersection = new HashSet<>(firstIds);
        intersection.retainAll(secondIds);
        Set<Long> union = new HashSet<>(firstIds);
        union.addAll(secondIds);
        List<Long> combined = new ArrayList<>(firstIds);
        combined.addAll(secondIds);
        assertAll(
                () -> assertEquals(Set.of(), intersection),
                () -> assertEquals(Set.copyOf(expectedIds), union),
                () -> assertEquals(expectedIds, combined)
        );

        if (sort != ComplexSort.COMPLETION_DATE_DESC) {
            List<ComplexSummaryRow> afterNull = repository.findPage(
                    noFilters(SEOUL_BOUNDS),
                    sort,
                    cursorOf(second.getLast(), sort),
                    10
            );
            assertEquals(List.of(), afterNull);
        }
    }

    @Test
    void keyword는_단지명과_도로명주소를_대소문자_없이_부분_검색한다() {
        HousingComplex nameMatch = persistComplex(
                "SeoUL Forest",
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        HousingComplex addressMatch = persistComplex(
                "주소 검색 단지",
                "서울특별시 강남구 TeHeRaN-ro 1",
                "11",
                "11680",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistComplex("무관한 단지", "37.500000", "126.900000");
        entityManager.flush();

        assertBothQueryPaths(directFilters("seoul", null, Set.of(), Set.of(), Set.of(), null, null, null),
                nameMatch.getId());
        assertBothQueryPaths(directFilters("teheran", null, Set.of(), Set.of(), Set.of(), null, null, null),
                addressMatch.getId());
    }

    @Test
    void keyword의_퍼센트_밑줄_역슬래시는_wildcard가_아닌_문자_그대로_검색한다() {
        HousingComplex percent = persistComplex("퍼센트%단지", "37.500000", "126.900000");
        persistComplex("퍼센트X단지", "37.500000", "126.900000");
        HousingComplex underscore = persistComplex("밑줄_단지", "37.500000", "126.900000");
        persistComplex("밑줄X단지", "37.500000", "126.900000");
        HousingComplex backslash = persistComplex("역슬래시\\단지", "37.500000", "126.900000");
        persistComplex("역슬래시X단지", "37.500000", "126.900000");
        entityManager.flush();

        assertBothQueryPaths(directFilters("%", null, Set.of(), Set.of(), Set.of(), null, null, null),
                percent.getId());
        assertBothQueryPaths(directFilters("_", null, Set.of(), Set.of(), Set.of(), null, null, null),
                underscore.getId());
        assertBothQueryPaths(directFilters("\\", null, Set.of(), Set.of(), Set.of(), null, null, null),
                backslash.getId());
    }

    @Test
    void 시도와_canonical_legacy_시군구_코드_집합을_함께_검색한다() {
        HousingComplex firstDistrict = persistComplex(
                "서울 중구",
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        HousingComplex secondDistrict = persistComplex(
                "서울 종로구",
                "서울특별시 종로구 종로 1",
                "11",
                "11110",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistComplex(
                "경기 성남시",
                "경기도 성남시 분당구 판교역로 1",
                "41",
                "41135",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        entityManager.flush();

        assertBothQueryPaths(
                directFilters(null, "11", Set.of("11140", "11110"), Set.of(), Set.of(), null, null, null),
                firstDistrict.getId(),
                secondDistrict.getId()
        );
    }

    @Test
    void 임대유형은_canonical과_한글_legacy_저장값을_같은_enum으로_검색한다() {
        HousingComplex canonical = persistComplex(
                "canonical 임대유형",
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "HAPPY_HOUSING",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        HousingComplex legacy = persistComplex(
                "legacy 임대유형",
                "서울특별시 중구 세종대로 111",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistComplex(
                "다른 임대유형",
                "서울특별시 중구 세종대로 112",
                "11",
                "11140",
                "국민임대",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        entityManager.flush();

        assertBothQueryPaths(
                directFilters(null, null, Set.of(), Set.of(RentalType.HAPPY_HOUSING), Set.of(), null, null, null),
                canonical.getId(),
                legacy.getId()
        );
    }

    @Test
    void 공급기관은_canonical과_한글_legacy_저장값을_같은_enum으로_검색한다() {
        HousingComplex canonical = persistComplex(
                "canonical 공급기관",
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        HousingComplex legacy = persistComplex(
                "legacy 공급기관",
                "서울특별시 중구 세종대로 111",
                "11",
                "11140",
                "행복주택",
                "한국토지주택공사",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistComplex(
                "다른 공급기관",
                "서울특별시 중구 세종대로 112",
                "11",
                "11140",
                "행복주택",
                "SH",
                LocalDate.of(2020, 1, 1),
                true
        );
        entityManager.flush();

        assertBothQueryPaths(
                directFilters(null, null, Set.of(), Set.of(), Set.of(AgencyCode.LH), null, null, null),
                canonical.getId(),
                legacy.getId()
        );
    }

    @Test
    void 준공연도_범위는_from과_to_양끝을_포함한다() {
        persistComplexWithCompletionDate("범위 전", LocalDate.of(2019, 12, 31));
        HousingComplex fromBoundary = persistComplexWithCompletionDate("from 경계", LocalDate.of(2020, 1, 1));
        HousingComplex toBoundary = persistComplexWithCompletionDate("to 경계", LocalDate.of(2021, 12, 31));
        persistComplexWithCompletionDate("범위 후", LocalDate.of(2022, 1, 1));
        entityManager.flush();

        assertBothQueryPaths(
                directFilters(null, null, Set.of(), Set.of(), Set.of(), 2020, 2021, null),
                fromBoundary.getId(),
                toBoundary.getId()
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OneSidedBound.class)
    void 한쪽_범위_bound만_있어도_해당_조건만_독립적으로_적용한다(OneSidedBound bound) {
        OneSidedBoundFixture fixture = persistOneSidedBoundFixture(bound);
        entityManager.flush();

        assertBothQueryPaths(fixture.condition(), fixture.expectedComplexId());
    }

    @Test
    void 엘리베이터_설치여부는_true와_false를_정확히_구분한다() {
        HousingComplex installed = persistComplexWithElevator("엘리베이터 있음", true);
        HousingComplex notInstalled = persistComplexWithElevator("엘리베이터 없음", false);
        entityManager.flush();

        assertBothQueryPaths(directFilters(null, null, Set.of(), Set.of(), Set.of(), null, null, true),
                installed.getId());
        assertBothQueryPaths(directFilters(null, null, Set.of(), Set.of(), Set.of(), null, null, false),
                notInstalled.getId());
    }

    @Test
    void 같은_enum은_OR이고_다른_그룹과_연도범위는_AND이며_단지_ID는_중복되지_않는다() {
        HousingComplex happy = persistComplex(
                "행복주택 LH",
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        HousingComplex national = persistComplex(
                "국민임대 LH",
                "서울특별시 중구 세종대로 111",
                "11",
                "11140",
                "국민임대",
                "LH",
                LocalDate.of(2021, 1, 1),
                true
        );
        persistComplex(
                "행복주택 SH",
                "서울특별시 중구 세종대로 112",
                "11",
                "11140",
                "행복주택",
                "SH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistComplex(
                "오래된 행복주택 LH",
                "서울특별시 중구 세종대로 113",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2010, 1, 1),
                true
        );
        entityManager.flush();

        assertBothQueryPaths(
                directFilters(
                        null,
                        null,
                        Set.of(),
                        Set.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL),
                        Set.of(AgencyCode.LH),
                        2020,
                        2021,
                        null
                ),
                happy.getId(),
                national.getId()
        );
    }

    @Test
    void 모집유형은_대표공고의_canonical과_한글_legacy_저장값을_같은_enum으로_검색한다() {
        HousingComplex canonical = persistComplex("canonical 모집유형", "37.500000", "126.900000");
        HousingComplex legacy = persistComplex("legacy 모집유형", "37.500000", "126.900000");
        HousingComplex other = persistComplex("다른 모집유형", "37.500000", "126.900000");
        persistComplex("대표공고 없음", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                canonical,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "canonical-recruitment"
        );
        Announcement legacyAnnouncement = persistRepresentativeWithApplicationPeriod(
                legacy,
                "NEW",
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "legacy-recruitment"
        );
        persistRepresentativeWithApplicationPeriod(
                other,
                "WAITLIST",
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "other-recruitment"
        );
        entityManager.flush();
        updateRecruitmentType(legacyAnnouncement, "신규모집");

        assertBothQueryPaths(
                announcementFilters(Set.of(), Set.of(RecruitmentType.NEW)),
                canonical.getId(),
                legacy.getId()
        );
    }

    @Test
    void BEFORE_APPLICATION은_대표공고의_접수시작일이_today보다_뒤인_단지만_검색한다() {
        HousingComplex before = persistComplex("접수 전", "37.500000", "126.900000");
        HousingComplex applying = persistComplex("접수 중", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                before,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "before"
        );
        persistRepresentativeWithApplicationPeriod(
                applying,
                "NEW",
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 31),
                "not-before"
        );
        entityManager.flush();

        assertBothQueryPaths(
                announcementFilters(Set.of(ApplicationStatus.BEFORE_APPLICATION), Set.of()),
                before.getId()
        );
    }

    @Test
    void APPLYING은_대표공고의_접수시작일과_종료일_today_양끝을_포함한다() {
        HousingComplex startBoundary = persistComplex("시작일 경계", "37.500000", "126.900000");
        HousingComplex endBoundary = persistComplex("종료일 경계", "37.500000", "126.900000");
        HousingComplex before = persistComplex("접수 전", "37.500000", "126.900000");
        HousingComplex closed = persistComplex("접수 종료", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                startBoundary,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 30),
                "applying-start-boundary"
        );
        persistRepresentativeWithApplicationPeriod(
                endBoundary,
                "NEW",
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 27),
                "applying-end-boundary"
        );
        persistRepresentativeWithApplicationPeriod(
                before,
                "NEW",
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "applying-before"
        );
        persistRepresentativeWithApplicationPeriod(
                closed,
                "NEW",
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 26),
                "applying-closed"
        );
        entityManager.flush();

        assertBothQueryPaths(
                announcementFilters(Set.of(ApplicationStatus.APPLYING), Set.of()),
                startBoundary.getId(),
                endBoundary.getId()
        );
    }

    @Test
    void CLOSED는_대표공고의_접수종료일이_today보다_앞인_단지만_검색한다() {
        HousingComplex closed = persistComplex("접수 종료", "37.500000", "126.900000");
        HousingComplex applying = persistComplex("종료일 경계", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                closed,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 26),
                "closed"
        );
        persistRepresentativeWithApplicationPeriod(
                applying,
                "NEW",
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 27),
                "not-closed"
        );
        entityManager.flush();

        assertBothQueryPaths(
                announcementFilters(Set.of(ApplicationStatus.CLOSED), Set.of()),
                closed.getId()
        );
    }

    @Test
    void 여러_접수상태는_OR로_검색하고_대표공고가_없는_단지는_제외한다() {
        HousingComplex before = persistComplex("접수 전", "37.500000", "126.900000");
        HousingComplex applying = persistComplex("접수 중", "37.500000", "126.900000");
        HousingComplex closed = persistComplex("접수 종료", "37.500000", "126.900000");
        persistComplex("대표공고 없음", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                before,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "multiple-before"
        );
        persistRepresentativeWithApplicationPeriod(
                applying,
                "NEW",
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30),
                "multiple-applying"
        );
        persistRepresentativeWithApplicationPeriod(
                closed,
                "NEW",
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 26),
                "multiple-closed"
        );
        entityManager.flush();

        assertBothQueryPaths(
                announcementFilters(
                        Set.of(ApplicationStatus.BEFORE_APPLICATION, ApplicationStatus.CLOSED),
                        Set.of()
                ),
                before.getId(),
                closed.getId()
        );
    }

    @Test
    void 과거공고가_조건을_만족해도_최신_대표공고가_불일치하면_제외한다() {
        HousingComplex complex = persistComplex("과거 조건 일치 단지", "37.500000", "126.900000");
        persistRepresentativeWithApplicationPeriod(
                complex,
                "NEW",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30),
                "historical-match"
        );
        persistRepresentativeWithApplicationPeriod(
                complex,
                "WAITLIST",
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31),
                "current-mismatch"
        );
        entityManager.flush();

        assertBothQueryPaths(announcementFilters(Set.of(), Set.of(RecruitmentType.NEW)));
        assertBothQueryPaths(announcementFilters(Set.of(ApplicationStatus.APPLYING), Set.of()));
    }

    @Test
    void 면적_범위는_실제_주택형_한_개가_양끝을_만족해야_한다() {
        HousingComplex gap = persistComplex("면적 gap 단지", "37.500000", "126.900000");
        persistHousingType(gap, "20A", "20.00");
        persistHousingType(gap, "60A", "60.00");
        HousingComplex matched = persistComplex("면적 일치 단지", "37.500000", "126.900000");
        persistHousingType(matched, "30A", "30.00");
        persistHousingType(matched, "50A", "50.00");
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(null, null, null, null, new BigDecimal("30.00"), new BigDecimal("50.00")),
                matched.getId()
        );
    }

    @Test
    void 보증금과_월세는_대표공고의_같은_공급대상이_양끝을_동시에_만족해야_한다() {
        HousingComplex crossTarget = persistComplex("교차 공급대상", "37.500000", "126.900000");
        Announcement crossAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "cross-target"
        );
        SupplyRow crossRow = persistSupplyRow(crossAnnouncement, crossTarget, null, "cross-target-row", 1);
        persistSupplyTarget(crossRow, "보증금만 일치", "50000000", "100000", 1);
        persistSupplyTarget(crossRow, "월세만 일치", "40000000", "300000", 2);

        HousingComplex matched = persistComplex("동일 공급대상", "37.500000", "126.900000");
        Announcement matchedAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "same-target"
        );
        SupplyRow matchedRow = persistSupplyRow(matchedAnnouncement, matched, null, "same-target-row", 1);
        persistSupplyTarget(matchedRow, "양끝 일치", "50000000", "300000", 1);
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(
                        new BigDecimal("50000000"),
                        new BigDecimal("60000000"),
                        new BigDecimal("200000"),
                        new BigDecimal("300000"),
                        null,
                        null
                ),
                matched.getId()
        );
    }

    @Test
    void 가격_filter는_선택된_대표공고의_공급대상만_검색한다() {
        HousingComplex historicalMatch = persistComplex("과거 가격만 일치", "37.500000", "126.900000");
        Announcement olderAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "older-price-match"
        );
        SupplyRow olderRow = persistSupplyRow(
                olderAnnouncement,
                historicalMatch,
                null,
                "older-price-match-row",
                1
        );
        persistSupplyTarget(olderRow, "과거 가격 일치", "50000000", "200000", 1);
        Announcement latestAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "latest-price-mismatch"
        );
        SupplyRow latestRow = persistSupplyRow(
                latestAnnouncement,
                historicalMatch,
                null,
                "latest-price-mismatch-row",
                1
        );
        persistSupplyTarget(latestRow, "대표 가격 불일치", "40000000", "200000", 1);

        HousingComplex representativeMatch = persistComplex("대표 가격 일치", "37.500000", "126.900000");
        Announcement matchingRepresentative = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 3),
                "representative-price-match"
        );
        SupplyRow matchingRow = persistSupplyRow(
                matchingRepresentative,
                representativeMatch,
                null,
                "representative-price-match-row",
                1
        );
        persistSupplyTarget(matchingRow, "대표 가격 일치", "50000000", "200000", 1);
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(
                        new BigDecimal("50000000"),
                        new BigDecimal("50000000"),
                        null,
                        null,
                        null,
                        null
                ),
                representativeMatch.getId()
        );
    }

    @Test
    void 가격과_면적은_대표공고의_같은_공급행에_연결된_주택형에서_만족해야_한다() {
        HousingComplex crossRow = persistComplex("교차 공급행", "37.500000", "126.900000");
        HousingType priceOnlyType = persistHousingType(crossRow, "20A", "20.00");
        HousingType areaOnlyType = persistHousingType(crossRow, "40A", "40.00");
        Announcement crossAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "cross-row"
        );
        SupplyRow priceOnlyRow = persistSupplyRow(
                crossAnnouncement,
                crossRow,
                priceOnlyType,
                "price-only-row",
                1
        );
        SupplyRow areaOnlyRow = persistSupplyRow(
                crossAnnouncement,
                crossRow,
                areaOnlyType,
                "area-only-row",
                2
        );
        persistSupplyTarget(priceOnlyRow, "가격만 일치", "50000000", "200000", 1);
        persistSupplyTarget(areaOnlyRow, "면적만 일치", "40000000", "200000", 1);

        HousingComplex matched = persistComplex("같은 공급행", "37.500000", "126.900000");
        HousingType matchedType = persistHousingType(matched, "50A", "50.00");
        Announcement matchedAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "same-row"
        );
        SupplyRow matchedRow = persistSupplyRow(
                matchedAnnouncement,
                matched,
                matchedType,
                "same-row",
                1
        );
        persistSupplyTarget(matchedRow, "가격과 면적 일치", "60000000", "200000", 1);
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(
                        new BigDecimal("50000000"),
                        new BigDecimal("60000000"),
                        null,
                        null,
                        new BigDecimal("30.00"),
                        new BigDecimal("50.00")
                ),
                matched.getId()
        );
    }

    @Test
    void null_가격은_숫자_필터와_일치하지_않는다() {
        HousingComplex nullPrice = persistComplex("가격 null", "37.500000", "126.900000");
        Announcement nullAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "null-price"
        );
        SupplyRow nullRow = persistSupplyRow(nullAnnouncement, nullPrice, null, "null-price-row", 1);
        persistNullableSupplyTarget(nullRow, "가격 없음", null, null, 1);

        HousingComplex matched = persistComplex("가격 있음", "37.500000", "126.900000");
        Announcement matchedAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "nonnull-price"
        );
        SupplyRow matchedRow = persistSupplyRow(matchedAnnouncement, matched, null, "nonnull-price-row", 1);
        persistSupplyTarget(matchedRow, "가격 있음", "0", "0", 1);
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(new BigDecimal("0"), null, new BigDecimal("0"), null, null, null),
                matched.getId()
        );
    }

    @Test
    void 가격만_검색할_때는_대표공고_공급행의_주택형이_없어도_일치한다() {
        HousingComplex complex = persistComplex("주택형 없는 가격", "37.500000", "126.900000");
        Announcement announcement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "price-without-housing-type"
        );
        SupplyRow supplyRow = persistSupplyRow(
                announcement,
                complex,
                null,
                "price-without-housing-type-row",
                1
        );
        persistSupplyTarget(supplyRow, "가격 일치", "50000000", "200000", 1);

        HousingComplex other = persistComplex("주택형 없는 다른 가격", "37.500000", "126.900000");
        Announcement otherAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "other-price-without-housing-type"
        );
        SupplyRow otherRow = persistSupplyRow(
                otherAnnouncement,
                other,
                null,
                "other-price-without-housing-type-row",
                1
        );
        persistSupplyTarget(otherRow, "가격 불일치", "40000000", "200000", 1);
        entityManager.flush();

        assertBothQueryPaths(
                priceAreaFilters(new BigDecimal("50000000"), new BigDecimal("50000000"), null, null, null, null),
                complex.getId()
        );
    }

    @Test
    void 가격과_면적_filter가_있어도_응답_범위는_대표공고와_전체_주택형으로_집계한다() {
        HousingComplex complex = persistComplex("집계 유지 단지", "37.500000", "126.900000");
        HousingType small = persistHousingType(complex, "20A", "20.00");
        HousingType large = persistHousingType(complex, "60A", "60.00");
        Announcement announcement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "unfiltered-aggregates"
        );
        SupplyRow smallRow = persistSupplyRow(announcement, complex, small, "small-row", 1);
        SupplyRow largeRow = persistSupplyRow(announcement, complex, large, "large-row", 2);
        persistSupplyTarget(smallRow, "필터 일치", "10000000", "100000", 1);
        persistSupplyTarget(largeRow, "집계에만 포함", "50000000", "500000", 1);

        HousingComplex other = persistComplex("필터 불일치 단지", "37.500000", "126.900000");
        HousingType otherType = persistHousingType(other, "20A", "20.00");
        Announcement otherAnnouncement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 2),
                "filtered-out-aggregate"
        );
        SupplyRow otherRow = persistSupplyRow(otherAnnouncement, other, otherType, "filtered-out-row", 1);
        persistSupplyTarget(otherRow, "필터 불일치", "90000000", "900000", 1);
        entityManager.flush();
        HousingComplexSearchCondition condition = priceAreaFilters(
                new BigDecimal("10000000"),
                new BigDecimal("10000000"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("20.00"),
                new BigDecimal("20.00")
        );

        List<ComplexSummaryRow> mapRows = repository.findAll(condition);
        List<ComplexSummaryRow> listRows = repository.findPage(
                condition,
                ComplexSort.LATEST_ANNOUNCEMENT,
                null,
                100
        );
        ComplexSummaryRow mapRow = mapRows.getFirst();
        ComplexSummaryRow listRow = listRows.getFirst();

        assertAll(
                () -> assertEquals(1, mapRows.size()),
                () -> assertEquals(1, listRows.size()),
                () -> assertEquals(complex.getId(), mapRow.complexId()),
                () -> assertEquals(complex.getId(), listRow.complexId()),
                () -> assertAggregateRanges(mapRow),
                () -> assertAggregateRanges(listRow)
        );
    }

    private ActiveAnnouncementFixture persistActiveAnnouncementFixture() {
        return new ActiveAnnouncementFixture(
                persistActiveBoundaryComplexes(),
                persistInactiveAnnouncementComplexes()
        );
    }

    private List<Long> persistActiveBoundaryComplexes() {
        HousingComplex startBoundary = persistComplex("모집 시작일 경계", "37.500000", "126.900000");
        HousingComplex endBoundary = persistComplex("모집 종료일 경계", "37.505000", "126.905000");
        persistApplicationPeriod(startBoundary, LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 30), "start-boundary-announcement");
        persistApplicationPeriod(endBoundary, LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 27), "end-boundary-announcement");
        return List.of(startBoundary.getId(), endBoundary.getId());
    }

    private List<Long> persistInactiveAnnouncementComplexes() {
        HousingComplex beforeApplication = persistComplex("접수 전", "37.510000", "126.910000");
        HousingComplex closed = persistComplex("접수 종료", "37.520000", "126.920000");
        HousingComplex withoutAnnouncement = persistComplex("공고 없음", "37.530000", "126.930000");
        HousingComplex cancelled = persistComplex("취소 공고", "37.540000", "126.940000");
        persistApplicationPeriod(beforeApplication, LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 31), "before-announcement");
        persistApplicationPeriod(closed, LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 26), "closed-announcement");
        persistCancelledAnnouncement(cancelled);
        return List.of(
                beforeApplication.getId(),
                closed.getId(),
                withoutAnnouncement.getId(),
                cancelled.getId()
        );
    }

    private void persistCancelledAnnouncement(HousingComplex complex) {
        Announcement original = persistRepresentative(
                complex, "ORIGINAL", LocalDate.of(2026, 8, 1), "inactive-original"
        );
        Announcement cancellation = persistAnnouncement(
                original, "CANCELLATION", LocalDate.of(2026, 8, 2), "inactive-cancellation"
        );
        persistSupplyRow(cancellation, complex, null, "inactive-cancellation-row", 1);
    }

    private void persistApplicationPeriod(
            HousingComplex complex,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            String suffix
    ) {
        persistRepresentativeWithApplicationPeriod(
                complex,
                "NEW",
                LocalDate.of(2026, 8, 1),
                applicationStartDate,
                applicationEndDate,
                suffix
        );
    }

    private HousingComplex persistComplex(String name, String latitude, String longitude) {
        return persistComplex(
                name,
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true,
                latitude,
                longitude
        );
    }

    private HousingComplex persistComplex(
            String name,
            String roadAddress,
            String provinceCode,
            String cityCountyDistrictCode,
            String supplyType,
            String provider,
            LocalDate completionDate,
            boolean elevatorInstalled
    ) {
        return persistComplex(
                name,
                roadAddress,
                provinceCode,
                cityCountyDistrictCode,
                supplyType,
                provider,
                completionDate,
                elevatorInstalled,
                "37.500000",
                "126.900000"
        );
    }

    private HousingComplex persistComplex(
            String name,
            String roadAddress,
            String provinceCode,
            String cityCountyDistrictCode,
            String supplyType,
            String provider,
            LocalDate completionDate,
            boolean elevatorInstalled,
            String latitude,
            String longitude
    ) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                supplyType,
                Address.create(
                        roadAddress,
                        "1114010100100010000",
                        "1114010100",
                        provinceCode,
                        cityCountyDistrictCode,
                        new BigDecimal(latitude),
                        new BigDecimal(longitude)
                ),
                100,
                provider,
                completionDate,
                "개별난방",
                "아파트",
                "계단식",
                elevatorInstalled,
                80,
                null,
                null
        );
        entityManager.persist(complex);
        return complex;
    }

    private HousingComplex persistComplexWithCompletionDate(String name, LocalDate completionDate) {
        return persistComplex(
                name,
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                completionDate,
                true
        );
    }

    private HousingComplex persistComplexWithElevator(String name, boolean elevatorInstalled) {
        return persistComplex(
                name,
                "서울특별시 중구 세종대로 110",
                "11",
                "11140",
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                elevatorInstalled
        );
    }

    private List<Long> persistSortOrderFixture(ComplexSort sort) {
        return switch (sort) {
            case LATEST_ANNOUNCEMENT -> persistLatestAnnouncementSortFixture();
            case DEPOSIT_ASC -> persistDepositSortFixture();
            case MONTHLY_RENT_ASC -> persistMonthlyRentSortFixture();
            case AREA_DESC -> persistAreaSortFixture();
            case COMPLETION_DATE_DESC -> persistCompletionDateSortFixture();
        };
    }

    private List<Long> persistLatestAnnouncementSortFixture() {
        HousingComplex older = persistComplex("최신공고 오래된 값", "37.500000", "126.900000");
        HousingComplex tieSmaller = persistComplex("최신공고 동률 작은 ID", "37.500000", "126.900000");
        HousingComplex tieGreater = persistComplex("최신공고 동률 큰 ID", "37.500000", "126.900000");
        HousingComplex noAnnouncement = persistComplex("최신공고 null", "37.500000", "126.900000");
        persistRepresentative(older, "ORIGINAL", LocalDate.of(2026, 8, 1), "sort-latest-older");
        persistRepresentative(tieSmaller, "ORIGINAL", LocalDate.of(2026, 8, 2), "sort-latest-tie-smaller");
        persistRepresentative(tieGreater, "ORIGINAL", LocalDate.of(2026, 8, 2), "sort-latest-tie-greater");
        return List.of(tieGreater.getId(), tieSmaller.getId(), older.getId(), noAnnouncement.getId());
    }

    private List<Long> persistDepositSortFixture() {
        HousingComplex lower = persistPricedComplex("보증금 낮은 값", "10000000", "300000", "deposit-lower");
        HousingComplex tieSmaller = persistPricedComplex(
                "보증금 동률 작은 ID", "50000000", "200000", "deposit-tie-smaller");
        HousingComplex tieGreater = persistPricedComplex(
                "보증금 동률 큰 ID", "50000000", "100000", "deposit-tie-greater");
        HousingComplex noPrice = persistComplex("보증금 null", "37.500000", "126.900000");
        return List.of(lower.getId(), tieGreater.getId(), tieSmaller.getId(), noPrice.getId());
    }

    private List<Long> persistMonthlyRentSortFixture() {
        HousingComplex lower = persistPricedComplex("월세 낮은 값", "70000000", "100000", "rent-lower");
        HousingComplex tieSmaller = persistPricedComplex(
                "월세 동률 작은 ID", "60000000", "300000", "rent-tie-smaller");
        HousingComplex tieGreater = persistPricedComplex(
                "월세 동률 큰 ID", "50000000", "300000", "rent-tie-greater");
        HousingComplex noPrice = persistComplex("월세 null", "37.500000", "126.900000");
        return List.of(lower.getId(), tieGreater.getId(), tieSmaller.getId(), noPrice.getId());
    }

    private List<Long> persistAreaSortFixture() {
        HousingComplex lower = persistComplex("면적 낮은 값", "37.500000", "126.900000");
        HousingComplex tieSmaller = persistComplex("면적 동률 작은 ID", "37.500000", "126.900000");
        HousingComplex tieGreater = persistComplex("면적 동률 큰 ID", "37.500000", "126.900000");
        HousingComplex noArea = persistComplex("면적 null", "37.500000", "126.900000");
        persistHousingType(lower, "면적 낮은 값", "36.12");
        persistHousingType(tieSmaller, "면적 동률 작은 ID", "44.87");
        persistHousingType(tieGreater, "면적 동률 큰 ID", "44.87");
        return List.of(tieGreater.getId(), tieSmaller.getId(), lower.getId(), noArea.getId());
    }

    private List<Long> persistCompletionDateSortFixture() {
        HousingComplex tieSmaller = persistComplexWithCompletionDate(
                "준공일 동률 작은 ID", LocalDate.of(2020, 1, 1));
        HousingComplex tieGreater = persistComplexWithCompletionDate(
                "준공일 동률 큰 ID", LocalDate.of(2020, 1, 1));
        HousingComplex older = persistComplexWithCompletionDate("준공일 오래된 값", LocalDate.of(2018, 1, 1));
        return List.of(tieGreater.getId(), tieSmaller.getId(), older.getId());
    }

    private HousingComplex persistPricedComplex(
            String name,
            String rentalDeposit,
            String monthlyRent,
            String suffix
    ) {
        HousingComplex complex = persistComplex(name, "37.500000", "126.900000");
        Announcement announcement = persistRepresentative(
                complex,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                suffix
        );
        SupplyRow supplyRow = persistSupplyRow(announcement, complex, null, suffix + "-price-row", 2);
        persistSupplyTarget(supplyRow, "정렬 공급대상", rentalDeposit, monthlyRent, 1);
        return complex;
    }

    private OneSidedBoundFixture persistOneSidedBoundFixture(OneSidedBound bound) {
        return switch (bound) {
            case BUILT_YEAR_FROM -> persistBuiltYearBoundFixture(bound, 2020, 2019, 2020, null);
            case BUILT_YEAR_TO -> persistBuiltYearBoundFixture(bound, 2020, 2021, null, 2020);
            case MIN_EXCLUSIVE_AREA -> persistAreaBoundFixture(bound, "30.00", "29.99", "30.00", null);
            case MAX_EXCLUSIVE_AREA -> persistAreaBoundFixture(bound, "30.00", "30.01", null, "30.00");
            case MIN_DEPOSIT -> persistPriceBoundFixture(
                    bound,
                    "50000000",
                    "100000",
                    "49999999",
                    "100000",
                    priceAreaFilters(new BigDecimal("50000000"), null, null, null, null, null)
            );
            case MAX_DEPOSIT -> persistPriceBoundFixture(
                    bound,
                    "50000000",
                    "100000",
                    "50000001",
                    "100000",
                    priceAreaFilters(null, new BigDecimal("50000000"), null, null, null, null)
            );
            case MIN_MONTHLY_RENT -> persistPriceBoundFixture(
                    bound,
                    "50000000",
                    "100000",
                    "50000000",
                    "99999",
                    priceAreaFilters(null, null, new BigDecimal("100000"), null, null, null)
            );
            case MAX_MONTHLY_RENT -> persistPriceBoundFixture(
                    bound,
                    "50000000",
                    "100000",
                    "50000000",
                    "100001",
                    priceAreaFilters(null, null, null, new BigDecimal("100000"), null, null)
            );
        };
    }

    private OneSidedBoundFixture persistBuiltYearBoundFixture(
            OneSidedBound bound,
            int matchingYear,
            int excludedYear,
            Integer builtYearFrom,
            Integer builtYearTo
    ) {
        HousingComplex matching = persistComplexWithCompletionDate(
                bound + " 일치",
                LocalDate.of(matchingYear, 1, 1)
        );
        persistComplexWithCompletionDate(bound + " 불일치", LocalDate.of(excludedYear, 1, 1));
        HousingComplexSearchCondition condition = directFilters(
                null,
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                builtYearFrom,
                builtYearTo,
                null
        );
        return new OneSidedBoundFixture(condition, matching.getId());
    }

    private OneSidedBoundFixture persistAreaBoundFixture(
            OneSidedBound bound,
            String matchingArea,
            String excludedArea,
            String minExclusiveArea,
            String maxExclusiveArea
    ) {
        HousingComplex matching = persistComplex(bound + " 일치", "37.500000", "126.900000");
        persistHousingType(matching, "일치 주택형", matchingArea);
        HousingComplex excluded = persistComplex(bound + " 불일치", "37.500000", "126.900000");
        persistHousingType(excluded, "불일치 주택형", excludedArea);
        HousingComplexSearchCondition condition = priceAreaFilters(
                null,
                null,
                null,
                null,
                decimalOrNull(minExclusiveArea),
                decimalOrNull(maxExclusiveArea)
        );
        return new OneSidedBoundFixture(condition, matching.getId());
    }

    private OneSidedBoundFixture persistPriceBoundFixture(
            OneSidedBound bound,
            String matchingDeposit,
            String matchingMonthlyRent,
            String excludedDeposit,
            String excludedMonthlyRent,
            HousingComplexSearchCondition condition
    ) {
        HousingComplex matching = persistPricedComplex(
                bound + " 일치",
                matchingDeposit,
                matchingMonthlyRent
        );
        persistPricedComplex(bound + " 불일치", excludedDeposit, excludedMonthlyRent);
        return new OneSidedBoundFixture(condition, matching.getId());
    }

    private HousingComplex persistPricedComplex(String suffix, String rentalDeposit, String monthlyRent) {
        HousingComplex complex = persistComplex(suffix, "37.500000", "126.900000");
        Announcement announcement = persistAnnouncement(
                null,
                "ORIGINAL",
                LocalDate.of(2026, 8, 1),
                "one-sided-" + suffix
        );
        SupplyRow supplyRow = persistSupplyRow(
                announcement,
                complex,
                null,
                "one-sided-" + suffix + "-row",
                1
        );
        persistSupplyTarget(supplyRow, "한쪽 가격 조건", rentalDeposit, monthlyRent, 1);
        return complex;
    }

    private BigDecimal decimalOrNull(String value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value);
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

    private Announcement persistRepresentativeWithApplicationPeriod(
            HousingComplex complex,
            String recruitmentType,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            String suffix
    ) {
        Announcement announcement = persistAnnouncement(
                null,
                "ORIGINAL",
                recruitmentType,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                suffix
        );
        persistSupplyRow(announcement, complex, null, suffix + "-row", 1);
        return announcement;
    }

    private Announcement persistAnnouncement(
            Announcement previousAnnouncement,
            String status,
            String recruitmentType,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
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
                recruitmentType,
                "LH",
                postedDate,
                applicationStartDate,
                applicationEndDate,
                postedDate.plusMonths(1),
                "https://example.com/announcements/" + suffix,
                null,
                0,
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)
        );
        entityManager.persist(announcement);
        return announcement;
    }

    private void updateRecruitmentType(Announcement announcement, String storedValue) {
        entityManager.createNativeQuery("UPDATE announcements SET recruitment_type = :storedValue WHERE id = :id")
                .setParameter("storedValue", storedValue)
                .setParameter("id", announcement.getId())
                .executeUpdate();
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

    private void persistNullableSupplyTarget(
            SupplyRow supplyRow,
            String target,
            BigDecimal rentalDeposit,
            BigDecimal monthlyRent,
            int displayOrder
    ) {
        entityManager.persist(SupplyTarget.create(
                supplyRow,
                target,
                "1순위",
                5,
                5,
                rentalDeposit,
                monthlyRent,
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
        return cursorOf(row, ComplexSort.LATEST_ANNOUNCEMENT);
    }

    private ComplexSummaryCursor cursorOf(ComplexSummaryRow row, ComplexSort sort) {
        ComplexSummaryCursor.SortValue primaryValue = switch (sort) {
            case LATEST_ANNOUNCEMENT -> dateValue(row.postedDate());
            case DEPOSIT_ASC -> decimalValue(row.depositMin());
            case MONTHLY_RENT_ASC -> decimalValue(row.monthlyRentMin());
            case AREA_DESC -> decimalValue(row.exclusiveAreaMax());
            case COMPLETION_DATE_DESC -> dateValue(row.completionDate());
        };
        return new ComplexSummaryCursor(sort, primaryValue, row.complexId());
    }

    private ComplexSummaryCursor.DateValue dateValue(LocalDate value) {
        if (value == null) {
            return null;
        }
        return new ComplexSummaryCursor.DateValue(value);
    }

    private ComplexSummaryCursor.DecimalValue decimalValue(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return new ComplexSummaryCursor.DecimalValue(value);
    }

    private HousingComplexSearchCondition noFilters(MapBounds bounds) {
        return new HousingComplexSearchCondition(
                bounds,
                new HousingComplexFilterCondition(
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 8, 27)
                )
        );
    }

    private HousingComplexSearchCondition integratedSearchCondition(
            Set<ApplicationStatus> applicationStatuses,
            Boolean hasActiveAnnouncement
    ) {
        return new HousingComplexSearchCondition(
                SEOUL_BOUNDS,
                new HousingComplexFilterCondition(
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        applicationStatuses,
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        hasActiveAnnouncement,
                        LocalDate.of(2026, 8, 27)
                )
        );
    }

    private HousingComplexSearchCondition directFilters(
            String keyword,
            String provinceCode,
            Set<String> cityCountyDistrictCodes,
            Set<RentalType> rentalTypes,
            Set<AgencyCode> agencyCodes,
            Integer builtYearFrom,
            Integer builtYearTo,
            Boolean hasElevator
    ) {
        return new HousingComplexSearchCondition(
                SEOUL_BOUNDS,
                new HousingComplexFilterCondition(
                        keyword,
                        provinceCode,
                        cityCountyDistrictCodes,
                        rentalTypes,
                        Set.of(),
                        agencyCodes,
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        builtYearFrom,
                        builtYearTo,
                        hasElevator,
                        null,
                        LocalDate.of(2026, 8, 27)
                )
        );
    }

    private HousingComplexSearchCondition announcementFilters(
            Set<ApplicationStatus> applicationStatuses,
            Set<RecruitmentType> recruitmentTypes
    ) {
        return new HousingComplexSearchCondition(
                SEOUL_BOUNDS,
                new HousingComplexFilterCondition(
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        applicationStatuses,
                        Set.of(),
                        recruitmentTypes,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 8, 27)
                )
        );
    }

    private HousingComplexSearchCondition priceAreaFilters(
            BigDecimal minDeposit,
            BigDecimal maxDeposit,
            BigDecimal minMonthlyRent,
            BigDecimal maxMonthlyRent,
            BigDecimal minExclusiveArea,
            BigDecimal maxExclusiveArea
    ) {
        return new HousingComplexSearchCondition(
                SEOUL_BOUNDS,
                new HousingComplexFilterCondition(
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        minDeposit,
                        maxDeposit,
                        minMonthlyRent,
                        maxMonthlyRent,
                        minExclusiveArea,
                        maxExclusiveArea,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 8, 27)
                )
        );
    }

    private void assertBothQueryPaths(HousingComplexSearchCondition condition, Long... expectedIds) {
        assertBothQueryPaths(condition, List.of(expectedIds));
    }

    private void assertBothQueryPaths(HousingComplexSearchCondition condition, List<Long> expectedIds) {
        List<Long> mapIds = ids(repository.findAll(condition));
        List<Long> listIds = ids(repository.findPage(condition, ComplexSort.LATEST_ANNOUNCEMENT, null, 100));
        Set<Long> expected = Set.copyOf(expectedIds);

        assertAll(
                () -> assertEquals(expected, Set.copyOf(mapIds)),
                () -> assertEquals(expectedIds.size(), mapIds.size()),
                () -> assertEquals(expected, Set.copyOf(listIds)),
                () -> assertEquals(expectedIds.size(), listIds.size())
        );
    }

    private List<Long> ids(List<ComplexSummaryRow> rows) {
        return rows.stream().map(ComplexSummaryRow::complexId).toList();
    }

    private void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private void assertAggregateRanges(ComplexSummaryRow row) {
        assertAll(
                () -> assertBigDecimalEquals("20.00", row.exclusiveAreaMin()),
                () -> assertBigDecimalEquals("60.00", row.exclusiveAreaMax()),
                () -> assertBigDecimalEquals("10000000", row.depositMin()),
                () -> assertBigDecimalEquals("50000000", row.depositMax()),
                () -> assertBigDecimalEquals("100000", row.monthlyRentMin()),
                () -> assertBigDecimalEquals("500000", row.monthlyRentMax())
        );
    }

    private enum OneSidedBound {
        BUILT_YEAR_FROM,
        BUILT_YEAR_TO,
        MIN_EXCLUSIVE_AREA,
        MAX_EXCLUSIVE_AREA,
        MIN_DEPOSIT,
        MAX_DEPOSIT,
        MIN_MONTHLY_RENT,
        MAX_MONTHLY_RENT
    }

    private record OneSidedBoundFixture(
            HousingComplexSearchCondition condition,
            Long expectedComplexId
    ) {
    }

    private record ActiveAnnouncementFixture(
            List<Long> activeComplexIds,
            List<Long> inactiveComplexIds
    ) {
        private List<Long> allComplexIds() {
            List<Long> allComplexIds = new ArrayList<>(activeComplexIds);
            allComplexIds.addAll(inactiveComplexIds);
            return List.copyOf(allComplexIds);
        }
    }
}
