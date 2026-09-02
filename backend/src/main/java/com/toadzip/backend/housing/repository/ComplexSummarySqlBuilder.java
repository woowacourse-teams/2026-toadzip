package com.toadzip.backend.housing.repository;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.global.persistence.LegacyStoredValue;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;

@Component
final class ComplexSummarySqlBuilder {

    private static final String BASE_SUMMARY_QUERY = """
            WITH latest_leaf AS (
                SELECT announcement.*
                FROM announcements announcement
                WHERE NOT EXISTS (
                    SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = announcement.id
                )
                  AND announcement.status NOT IN ('CANCELLATION', '취소공고')
            ), representative AS (
                SELECT DISTINCT ON (supply_row.housing_complex_id)
                       supply_row.housing_complex_id,
                       announcement.id AS announcement_id,
                       announcement.status AS publication_type,
                       announcement.recruitment_type,
                       announcement.posted_date,
                       announcement.application_start_date,
                       announcement.application_end_date
                FROM supply_rows supply_row
                JOIN latest_leaf announcement ON announcement.id = supply_row.announcement_id
                WHERE supply_row.housing_complex_id IS NOT NULL
                ORDER BY supply_row.housing_complex_id, announcement.posted_date DESC, announcement.id DESC
            ), area_range AS (
                SELECT housing_complex_id,
                       MIN(exclusive_area) AS exclusive_area_min,
                       MAX(exclusive_area) AS exclusive_area_max
                FROM housing_types
                GROUP BY housing_complex_id
            ), price_range AS (
                SELECT supply_row.housing_complex_id,
                       MIN(supply_target.rental_deposit) AS deposit_min,
                       MAX(supply_target.rental_deposit) AS deposit_max,
                       MIN(supply_target.monthly_rent) AS monthly_rent_min,
                       MAX(supply_target.monthly_rent) AS monthly_rent_max
                FROM representative
                JOIN supply_rows supply_row
                  ON supply_row.housing_complex_id = representative.housing_complex_id
                 AND supply_row.announcement_id = representative.announcement_id
                JOIN supply_targets supply_target ON supply_target.supply_row_id = supply_row.id
                GROUP BY supply_row.housing_complex_id
            )
            SELECT housing_complex.id AS complex_id,
                   housing_complex.name,
                   housing_complex.image_url,
                   housing_complex.province_code,
                   housing_complex.city_county_district_code,
                   housing_complex.supply_type AS rental_type,
                   housing_complex.provider AS agency_code,
                   housing_complex.latitude,
                   housing_complex.longitude,
                   area_range.exclusive_area_min,
                   area_range.exclusive_area_max,
                   price_range.deposit_min,
                   price_range.deposit_max,
                   price_range.monthly_rent_min,
                   price_range.monthly_rent_max,
                   representative.announcement_id,
                   representative.publication_type,
                   representative.posted_date,
                   representative.application_start_date,
                   representative.application_end_date,
                   housing_complex.completion_date
            FROM housing_complexes housing_complex
            LEFT JOIN representative ON representative.housing_complex_id = housing_complex.id
            LEFT JOIN area_range ON area_range.housing_complex_id = housing_complex.id
            LEFT JOIN price_range ON price_range.housing_complex_id = housing_complex.id
            WHERE housing_complex.latitude BETWEEN :southWestLat AND :northEastLat
              AND housing_complex.longitude BETWEEN :southWestLng AND :northEastLng
            """;

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

    private static final String MAP_ORDER = """
            ORDER BY housing_complex.id
            """;

    ComplexSummarySqlQuery buildMapQuery(HousingComplexSearchCondition condition) {
        Map<String, Object> parameters = new HashMap<>(boundsParameters(condition.bounds()));
        return new ComplexSummarySqlQuery(
                BASE_SUMMARY_QUERY + filters(condition, parameters) + MAP_ORDER,
                parameters
        );
    }

    ComplexSummarySqlQuery buildListQuery(
            HousingComplexSearchCondition condition,
            ComplexSort sort,
            ComplexSummaryCursor cursor,
            int limit
    ) {
        Map<String, Object> parameters = new HashMap<>(boundsParameters(condition.bounds()));
        String filteredQuery = BASE_SUMMARY_QUERY + filters(condition, parameters);
        SortSpec sortSpec = sortSpec(sort);
        parameters.put("limit", limit);
        if (cursor == null) {
            return new ComplexSummarySqlQuery(filteredQuery + firstPage(sortSpec), parameters);
        }
        parameters.put("cursorComplexId", cursor.complexId());
        if (cursor.primaryValue() == null) {
            return new ComplexSummarySqlQuery(filteredQuery + pageAfterNull(sortSpec), parameters);
        }
        parameters.put("cursorValue", cursor.primaryValue().jdbcValue());
        return new ComplexSummarySqlQuery(filteredQuery + pageAfterValue(sortSpec), parameters);
    }

    private String firstPage(SortSpec sortSpec) {
        return orderBy(sortSpec) + "LIMIT :limit\n";
    }

    private String pageAfterValue(SortSpec sortSpec) {
        String expression = sortSpec.expression();
        return "  AND (\n"
                + "        " + expression + " " + comparisonOperator(sortSpec.direction()) + " :cursorValue\n"
                + "        OR (" + expression + " = :cursorValue AND housing_complex.id < :cursorComplexId)\n"
                + "        OR " + expression + " IS NULL\n"
                + "  )\n"
                + orderBy(sortSpec)
                + "LIMIT :limit\n";
    }

    private String pageAfterNull(SortSpec sortSpec) {
        return "  AND " + sortSpec.expression() + " IS NULL\n"
                + "  AND housing_complex.id < :cursorComplexId\n"
                + orderBy(sortSpec)
                + "LIMIT :limit\n";
    }

    private String comparisonOperator(Direction direction) {
        return switch (direction) {
            case ASC -> ">";
            case DESC -> "<";
        };
    }

    private String orderBy(SortSpec sortSpec) {
        return "ORDER BY " + sortSpec.expression() + " " + sortSpec.direction()
                + " NULLS LAST, housing_complex.id DESC\n";
    }

    private SortSpec sortSpec(ComplexSort sort) {
        return switch (sort) {
            case LATEST_ANNOUNCEMENT -> new SortSpec("representative.posted_date", Direction.DESC);
            case DEPOSIT_ASC -> new SortSpec("price_range.deposit_min", Direction.ASC);
            case MONTHLY_RENT_ASC -> new SortSpec("price_range.monthly_rent_min", Direction.ASC);
            case AREA_DESC -> new SortSpec("area_range.exclusive_area_max", Direction.DESC);
            case COMPLETION_DATE_DESC -> new SortSpec("housing_complex.completion_date", Direction.DESC);
        };
    }

    private String filters(HousingComplexSearchCondition condition, Map<String, Object> parameters) {
        StringBuilder filters = new StringBuilder();
        addKeywordFilter(condition, filters, parameters);
        addDirectComplexFilters(condition, filters, parameters);
        addAnnouncementFilters(condition, filters, parameters);
        addPriceAreaFilters(condition, filters, parameters);
        return filters.toString();
    }

    private void addKeywordFilter(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (condition.keyword() == null) {
            return;
        }
        filters.append(KEYWORD_FILTER);
        parameters.put("keywordPattern", keywordPattern(condition.keyword()));
    }

    private void addDirectComplexFilters(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        addFilter(condition.provinceCode(), PROVINCE_FILTER, "provinceCode", filters, parameters);
        addCollectionFilter(
                condition.cityCountyDistrictCodes(),
                DISTRICT_FILTER,
                "districtCodes",
                filters,
                parameters
        );
        addCollectionFilter(
                storedValues(condition.rentalTypes()),
                RENTAL_TYPE_FILTER,
                "rentalTypeValues",
                filters,
                parameters
        );
        addCollectionFilter(
                storedValues(condition.agencyCodes()),
                AGENCY_CODE_FILTER,
                "agencyCodeValues",
                filters,
                parameters
        );
        addFilter(condition.builtYearFrom(), BUILT_YEAR_FROM_FILTER, "builtYearFrom", filters, parameters);
        addFilter(condition.builtYearTo(), BUILT_YEAR_TO_FILTER, "builtYearTo", filters, parameters);
        addFilter(condition.hasElevator(), ELEVATOR_FILTER, "hasElevator", filters, parameters);
        addActiveAnnouncementFilter(condition, filters, parameters);
    }

    private void addActiveAnnouncementFilter(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (condition.hasActiveAnnouncement() == null) {
            return;
        }
        if (condition.hasActiveAnnouncement()) {
            filters.append(ACTIVE_ANNOUNCEMENT_FILTER);
        } else {
            filters.append(NO_ACTIVE_ANNOUNCEMENT_FILTER);
        }
        parameters.put("today", condition.today());
    }

    private void addAnnouncementFilters(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (condition.recruitmentTypes().isEmpty() && condition.applicationStatuses().isEmpty()) {
            return;
        }
        if (!condition.recruitmentTypes().isEmpty()) {
            filters.append(REPRESENTATIVE_REQUIRED_FILTER);
            addCollectionFilter(
                    storedValues(condition.recruitmentTypes()),
                    RECRUITMENT_TYPE_FILTER,
                    "recruitmentTypeValues",
                    filters,
                    parameters
            );
        }
        addApplicationStatusFilter(condition, filters, parameters);
    }

    private void addApplicationStatusFilter(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (condition.applicationStatuses().isEmpty()) {
            return;
        }
        StringJoiner statusFilters = new StringJoiner(
                "\n                    OR ",
                "              AND (\n                    ",
                "\n              )\n"
        );
        condition.applicationStatuses().stream()
                .map(this::applicationStatusFilter)
                .forEach(statusFilters::add);
        filters.append(statusFilters);
        parameters.put("today", condition.today());
    }

    private String applicationStatusFilter(ApplicationStatus status) {
        return switch (status) {
            case BEFORE_APPLICATION -> BEFORE_APPLICATION_FILTER;
            case APPLYING -> APPLYING_FILTER;
            case CLOSED -> CLOSED_FILTER;
            case CANCELLED -> CANCELLED_FILTER;
        };
    }

    private void addPriceAreaFilters(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (hasPriceFilter(condition)) {
            addSupplyTargetExists(condition, filters, parameters);
            return;
        }
        if (hasAreaFilter(condition)) {
            addAreaExists(condition, filters, parameters);
        }
    }

    private void addSupplyTargetExists(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        filters.append(SUPPLY_TARGET_EXISTS_START);
        addFilter(condition.minDeposit(), MIN_DEPOSIT_FILTER, "minDeposit", filters, parameters);
        addFilter(condition.maxDeposit(), MAX_DEPOSIT_FILTER, "maxDeposit", filters, parameters);
        addFilter(condition.minMonthlyRent(), MIN_MONTHLY_RENT_FILTER, "minMonthlyRent", filters, parameters);
        addFilter(condition.maxMonthlyRent(), MAX_MONTHLY_RENT_FILTER, "maxMonthlyRent", filters, parameters);
        addAreaBounds(condition, filters, parameters);
        filters.append(EXISTS_END);
    }

    private void addAreaExists(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        filters.append(AREA_EXISTS_START);
        addAreaBounds(condition, filters, parameters);
        filters.append(EXISTS_END);
    }

    private void addAreaBounds(
            HousingComplexSearchCondition condition,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        addFilter(
                condition.minExclusiveArea(),
                MIN_EXCLUSIVE_AREA_FILTER,
                "minExclusiveArea",
                filters,
                parameters
        );
        addFilter(
                condition.maxExclusiveArea(),
                MAX_EXCLUSIVE_AREA_FILTER,
                "maxExclusiveArea",
                filters,
                parameters
        );
    }

    private boolean hasPriceFilter(HousingComplexSearchCondition condition) {
        return condition.minDeposit() != null
                || condition.maxDeposit() != null
                || condition.minMonthlyRent() != null
                || condition.maxMonthlyRent() != null;
    }

    private boolean hasAreaFilter(HousingComplexSearchCondition condition) {
        return condition.minExclusiveArea() != null || condition.maxExclusiveArea() != null;
    }

    private void addFilter(
            Object value,
            String sql,
            String parameterName,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (value == null) {
            return;
        }
        filters.append(sql);
        parameters.put(parameterName, value);
    }

    private void addCollectionFilter(
            Set<?> values,
            String sql,
            String parameterName,
            StringBuilder filters,
            Map<String, Object> parameters
    ) {
        if (values.isEmpty()) {
            return;
        }
        filters.append(sql);
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

    private Map<String, Object> boundsParameters(MapBounds bounds) {
        return Map.of(
                "southWestLat", bounds.southWestLat(),
                "southWestLng", bounds.southWestLng(),
                "northEastLat", bounds.northEastLat(),
                "northEastLng", bounds.northEastLng()
        );
    }

    private enum Direction {
        ASC,
        DESC
    }

    private record SortSpec(String expression, Direction direction) {
    }
}
