package com.toadzip.backend.housing.repository;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.global.persistence.LegacyStoredValue;

@Component
final class HousingComplexFilterPredicateBuilder {

    private static final String KEYWORD_FILTER = """
              AND (
                    LOWER(housing_complex.name) LIKE :keywordPattern ESCAPE '\\'
                    OR LOWER(housing_complex.road_address) LIKE :keywordPattern ESCAPE '\\'
              )
            """;

    private static final String PROVINCE_FILTER = """
              AND housing_complex.province_code = :provinceCode
            """;

    private static final String DISTRICT_FILTER = """
              AND housing_complex.city_county_district_code IN (:districtCodes)
            """;

    private static final String RENTAL_TYPE_FILTER = """
              AND housing_complex.supply_type IN (:rentalTypeValues)
            """;

    private static final String AGENCY_CODE_FILTER = """
              AND housing_complex.provider IN (:agencyCodeValues)
            """;

    private static final String BUILT_YEAR_FROM_FILTER = """
              AND EXTRACT(YEAR FROM housing_complex.completion_date) >= :builtYearFrom
            """;

    private static final String BUILT_YEAR_TO_FILTER = """
              AND EXTRACT(YEAR FROM housing_complex.completion_date) <= :builtYearTo
            """;

    private static final String ELEVATOR_FILTER = """
              AND housing_complex.elevator_installed = :hasElevator
            """;

    private static final String ACTIVE_ANNOUNCEMENT_FILTER = """
              AND representative.announcement_id IS NOT NULL
              AND representative.application_start_date <= :today
              AND representative.application_end_date >= :today
            """;

    private static final String NO_ACTIVE_ANNOUNCEMENT_FILTER = """
              AND (
                    representative.announcement_id IS NULL
                    OR representative.application_start_date > :today
                    OR representative.application_end_date < :today
              )
            """;

    private static final String REPRESENTATIVE_REQUIRED_FILTER = """
              AND representative.announcement_id IS NOT NULL
            """;

    private static final String RECRUITMENT_TYPE_FILTER = """
              AND representative.recruitment_type IN (:recruitmentTypeValues)
            """;

    private static final String BEFORE_APPLICATION_FILTER =
            "representative.application_start_date > :today";

    private static final String APPLYING_FILTER = """
            (
                representative.application_start_date <= :today
                AND representative.application_end_date >= :today
            )
            """.strip();

    private static final String CLOSED_FILTER =
            "representative.application_end_date < :today";

    private static final String CANCELLED_FILTER = """
            EXISTS (
                SELECT 1
                FROM supply_rows cancelled_supply_row
                JOIN announcements cancelled_announcement
                  ON cancelled_announcement.id = cancelled_supply_row.announcement_id
                WHERE cancelled_supply_row.housing_complex_id = housing_complex.id
                  AND cancelled_announcement.status IN ('CANCELLATION', '취소공고')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM announcements cancelled_successor
                      WHERE cancelled_successor.previous_announcement_id = cancelled_announcement.id
                  )
            )
            """.strip();

    private static final String AREA_EXISTS_START = """
              AND EXISTS (
                    SELECT 1
                    FROM housing_types matched_housing_type
                    WHERE matched_housing_type.housing_complex_id = housing_complex.id
            """;

    private static final String SUPPLY_TARGET_EXISTS_START = """
              AND EXISTS (
                    SELECT 1
                    FROM supply_rows matched_supply_row
                    JOIN supply_targets matched_supply_target
                      ON matched_supply_target.supply_row_id = matched_supply_row.id
                    LEFT JOIN housing_types matched_housing_type
                      ON matched_housing_type.id = matched_supply_row.housing_type_id
                    WHERE matched_supply_row.housing_complex_id = housing_complex.id
                      AND matched_supply_row.announcement_id = representative.announcement_id
            """;

    private static final String EXISTS_END = """
              )
            """;

    private static final String MIN_DEPOSIT_FILTER = """
                      AND matched_supply_target.rental_deposit >= :minDeposit
            """;

    private static final String MAX_DEPOSIT_FILTER = """
                      AND matched_supply_target.rental_deposit <= :maxDeposit
            """;

    private static final String MIN_MONTHLY_RENT_FILTER = """
                      AND matched_supply_target.monthly_rent >= :minMonthlyRent
            """;

    private static final String MAX_MONTHLY_RENT_FILTER = """
                      AND matched_supply_target.monthly_rent <= :maxMonthlyRent
            """;

    private static final String MIN_EXCLUSIVE_AREA_FILTER = """
                      AND matched_housing_type.exclusive_area >= :minExclusiveArea
            """;

    private static final String MAX_EXCLUSIVE_AREA_FILTER = """
                      AND matched_housing_type.exclusive_area <= :maxExclusiveArea
            """;

    private static final Map<ApplicationStatus, String> APPLICATION_STATUS_FILTERS = applicationStatusFilters();

    HousingComplexFilterPredicate build(HousingComplexFilterCondition condition) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> parameters = new HashMap<>();
        addKeywordFilter(condition, sql, parameters);
        addDirectComplexFilters(condition, sql, parameters);
        addAnnouncementFilters(condition, sql, parameters);
        addPriceAreaFilters(condition, sql, parameters);
        return new HousingComplexFilterPredicate(sql.toString(), parameters);
    }

    private void addKeywordFilter(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (condition.keyword() == null) {
            return;
        }
        sql.append(KEYWORD_FILTER);
        parameters.put("keywordPattern", keywordPattern(condition.keyword()));
    }

    private void addDirectComplexFilters(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        addFilter(condition.provinceCode(), PROVINCE_FILTER, "provinceCode", sql, parameters);
        addCollectionFilter(condition.cityCountyDistrictCodes(), DISTRICT_FILTER, "districtCodes", sql, parameters);
        addStoredValueFilter(condition.rentalTypes(), RENTAL_TYPE_FILTER, "rentalTypeValues", sql, parameters);
        addStoredValueFilter(condition.agencyCodes(), AGENCY_CODE_FILTER, "agencyCodeValues", sql, parameters);
        addFilter(condition.builtYearFrom(), BUILT_YEAR_FROM_FILTER, "builtYearFrom", sql, parameters);
        addFilter(condition.builtYearTo(), BUILT_YEAR_TO_FILTER, "builtYearTo", sql, parameters);
        addFilter(condition.hasElevator(), ELEVATOR_FILTER, "hasElevator", sql, parameters);
        addActiveAnnouncementFilter(condition, sql, parameters);
    }

    private void addActiveAnnouncementFilter(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        Boolean hasActiveAnnouncement = condition.hasActiveAnnouncement();
        if (hasActiveAnnouncement == null) {
            return;
        }
        parameters.put("today", condition.today());
        appendActiveAnnouncementFilter(hasActiveAnnouncement, sql);
    }

    private void appendActiveAnnouncementFilter(Boolean hasActiveAnnouncement, StringBuilder sql) {
        if (hasActiveAnnouncement) {
            sql.append(ACTIVE_ANNOUNCEMENT_FILTER);
            return;
        }
        sql.append(NO_ACTIVE_ANNOUNCEMENT_FILTER);
    }

    private void addAnnouncementFilters(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (hasNoAnnouncementFilter(condition)) {
            return;
        }
        addRecruitmentTypeFilter(condition, sql, parameters);
        addApplicationStatusFilter(condition, sql, parameters);
    }

    private boolean hasNoAnnouncementFilter(HousingComplexFilterCondition condition) {
        return condition.recruitmentTypes().isEmpty() && condition.applicationStatuses().isEmpty();
    }

    private void addRecruitmentTypeFilter(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (condition.recruitmentTypes().isEmpty()) {
            return;
        }
        sql.append(REPRESENTATIVE_REQUIRED_FILTER);
        addStoredValueFilter(
                condition.recruitmentTypes(),
                RECRUITMENT_TYPE_FILTER,
                "recruitmentTypeValues",
                sql,
                parameters
        );
    }

    private void addApplicationStatusFilter(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (condition.applicationStatuses().isEmpty()) {
            return;
        }
        sql.append(joinedApplicationStatusFilters(condition.applicationStatuses()));
        parameters.put("today", condition.today());
    }

    private String joinedApplicationStatusFilters(Set<ApplicationStatus> statuses) {
        StringJoiner filters = new StringJoiner(
                "\n                    OR ",
                "              AND (\n                    ",
                "\n              )\n"
        );
        statuses.stream().map(APPLICATION_STATUS_FILTERS::get).forEach(filters::add);
        return filters.toString();
    }

    private void addPriceAreaFilters(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (hasPriceFilter(condition)) {
            addSupplyTargetExists(condition, sql, parameters);
            return;
        }
        if (hasAreaFilter(condition)) {
            addAreaExists(condition, sql, parameters);
        }
    }

    private void addSupplyTargetExists(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        sql.append(SUPPLY_TARGET_EXISTS_START);
        addFilter(condition.minDeposit(), MIN_DEPOSIT_FILTER, "minDeposit", sql, parameters);
        addFilter(condition.maxDeposit(), MAX_DEPOSIT_FILTER, "maxDeposit", sql, parameters);
        addFilter(condition.minMonthlyRent(), MIN_MONTHLY_RENT_FILTER, "minMonthlyRent", sql, parameters);
        addFilter(condition.maxMonthlyRent(), MAX_MONTHLY_RENT_FILTER, "maxMonthlyRent", sql, parameters);
        addAreaBounds(condition, sql, parameters);
        sql.append(EXISTS_END);
    }

    private void addAreaExists(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        sql.append(AREA_EXISTS_START);
        addAreaBounds(condition, sql, parameters);
        sql.append(EXISTS_END);
    }

    private void addAreaBounds(
            HousingComplexFilterCondition condition,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        addFilter(condition.minExclusiveArea(), MIN_EXCLUSIVE_AREA_FILTER, "minExclusiveArea", sql, parameters);
        addFilter(condition.maxExclusiveArea(), MAX_EXCLUSIVE_AREA_FILTER, "maxExclusiveArea", sql, parameters);
    }

    private boolean hasPriceFilter(HousingComplexFilterCondition condition) {
        return condition.minDeposit() != null
                || condition.maxDeposit() != null
                || condition.minMonthlyRent() != null
                || condition.maxMonthlyRent() != null;
    }

    private boolean hasAreaFilter(HousingComplexFilterCondition condition) {
        return condition.minExclusiveArea() != null || condition.maxExclusiveArea() != null;
    }

    private void addStoredValueFilter(
            Set<? extends LegacyStoredValue> values,
            String filter,
            String parameterName,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        addCollectionFilter(storedValues(values), filter, parameterName, sql, parameters);
    }

    private void addFilter(
            Object value,
            String filter,
            String parameterName,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (value == null) {
            return;
        }
        sql.append(filter);
        parameters.put(parameterName, value);
    }

    private void addCollectionFilter(
            Set<?> values,
            String filter,
            String parameterName,
            StringBuilder sql,
            Map<String, Object> parameters
    ) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(filter);
        parameters.put(parameterName, values);
    }

    private Set<String> storedValues(Set<? extends LegacyStoredValue> values) {
        return values.stream()
                .flatMap(value -> value.storedValues().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String keywordPattern(String keyword) {
        String escaped = keyword.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static Map<ApplicationStatus, String> applicationStatusFilters() {
        Map<ApplicationStatus, String> filters = Map.of(
                ApplicationStatus.BEFORE_APPLICATION, BEFORE_APPLICATION_FILTER,
                ApplicationStatus.APPLYING, APPLYING_FILTER,
                ApplicationStatus.CLOSED, CLOSED_FILTER,
                ApplicationStatus.CANCELLED, CANCELLED_FILTER
        );
        requireCompleteApplicationStatusFilters(filters);
        return filters;
    }

    private static void requireCompleteApplicationStatusFilters(Map<ApplicationStatus, String> filters) {
        if (filters.keySet().equals(EnumSet.allOf(ApplicationStatus.class))) {
            return;
        }
        throw new IllegalStateException("모든 접수 상태의 SQL 필터가 필요합니다.");
    }
}
