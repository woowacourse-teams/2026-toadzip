package com.toadzip.backend.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;

class HousingComplexFilterPredicateBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    private final HousingComplexFilterPredicateBuilder builder = new HousingComplexFilterPredicateBuilder();

    @Test
    void 필터가_없으면_SQL과_파라미터가_비어있다() {
        HousingComplexFilterPredicate predicate = builder.build(noFilters());

        assertThat(predicate.sql()).isEmpty();
        assertThat(predicate.parameters()).isEmpty();
    }

    @Test
    void 비공간_필터만_SQL과_파라미터로_만든다() {
        HousingComplexFilterPredicate predicate = builder.build(directFilters());

        assertThat(predicate.sql())
                .containsSubsequence(
                        "LOWER(housing_complex.name)",
                        "housing_complex.province_code",
                        "housing_complex.city_county_district_code",
                        "housing_complex.supply_type",
                        "housing_complex.provider",
                        "EXTRACT(YEAR FROM housing_complex.completion_date)",
                        "housing_complex.elevator_installed"
                )
                .doesNotContain("southWestLat", "northEastLat", "ORDER BY", "LIMIT", "cursor");
        assertThat(predicate.parameters())
                .containsEntry("keywordPattern", "%서울\\%단지%")
                .containsEntry("provinceCode", "11")
                .containsEntry("districtCodes", Set.of("11110", "11140"))
                .containsEntry("builtYearFrom", 2020)
                .containsEntry("hasElevator", true)
                .doesNotContainKeys("southWestLat", "northEastLat", "limit", "cursorValue");
    }

    @Test
    void 모집중_공고_여부에_따라_서로_다른_조건을_만든다() {
        HousingComplexFilterPredicate active = builder.build(activeAnnouncement(true));
        HousingComplexFilterPredicate inactive = builder.build(activeAnnouncement(false));

        assertThat(active.sql())
                .contains("representative.announcement_id IS NOT NULL")
                .contains("representative.application_start_date <= :today");
        assertThat(inactive.sql())
                .contains("representative.announcement_id IS NULL")
                .contains("representative.application_start_date > :today");
        assertThat(active.parameters()).containsEntry("today", TODAY);
        assertThat(inactive.parameters()).containsEntry("today", TODAY);
    }

    @Test
    void 모든_접수상태를_SQL_조건으로_변환한다() {
        HousingComplexFilterPredicate predicate = builder.build(applicationStatuses());

        assertThat(predicate.sql())
                .contains("representative.application_start_date > :today")
                .contains("representative.application_start_date <= :today")
                .contains("representative.application_end_date < :today")
                .contains("cancelled_announcement.status IN ('CANCELLATION', '취소공고')");
        assertThat(predicate.parameters()).containsEntry("today", TODAY);
    }

    private HousingComplexFilterCondition noFilters() {
        return condition(null, null, Set.of(), Set.of(), Set.of(), Set.of(), null, null, null, null);
    }

    private HousingComplexFilterCondition directFilters() {
        return condition(
                "서울%단지",
                "11",
                Set.of("11110", "11140"),
                Set.of(RentalType.HAPPY_HOUSING),
                Set.of(),
                Set.of(AgencyCode.LH),
                2020,
                null,
                true,
                null
        );
    }

    private HousingComplexFilterCondition activeAnnouncement(boolean hasActiveAnnouncement) {
        return condition(
                null,
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                hasActiveAnnouncement
        );
    }

    private HousingComplexFilterCondition applicationStatuses() {
        return condition(
                null,
                null,
                Set.of(),
                Set.of(),
                EnumSet.allOf(ApplicationStatus.class),
                Set.of(),
                null,
                null,
                null,
                null
        );
    }

    private HousingComplexFilterCondition condition(
            String keyword,
            String provinceCode,
            Set<String> districtCodes,
            Set<RentalType> rentalTypes,
            Set<ApplicationStatus> applicationStatuses,
            Set<AgencyCode> agencyCodes,
            Integer builtYearFrom,
            Integer builtYearTo,
            Boolean hasElevator,
            Boolean hasActiveAnnouncement
    ) {
        return new HousingComplexFilterCondition(
                keyword,
                provinceCode,
                districtCodes,
                rentalTypes,
                applicationStatuses,
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
                hasActiveAnnouncement,
                TODAY
        );
    }
}
