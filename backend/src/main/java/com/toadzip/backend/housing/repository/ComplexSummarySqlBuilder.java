package com.toadzip.backend.housing.repository;

import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;

@Component
final class ComplexSummarySqlBuilder {

    private static final String BOUNDS_PREDICATE = """
              AND housing_complex.latitude BETWEEN :southWestLat AND :northEastLat
              AND housing_complex.longitude BETWEEN :southWestLng AND :northEastLng
            """;

    private static final String VIEWPORT_QUERY = """
            , viewport_complexes AS MATERIALIZED (
                SELECT housing_complex.*
                FROM housing_complexes housing_complex
                WHERE housing_complex.admin_deleted = false
            """ + BOUNDS_PREDICATE + ")\n";

    private static final String AREA_RANGE_COLUMNS = """
                   MIN(housing_type.exclusive_area) AS exclusive_area_min,
                   MAX(housing_type.exclusive_area) AS exclusive_area_max,
                   MIN(housing_type.basic_deposit) AS deposit_min,
                   MAX(housing_type.basic_deposit) AS deposit_max,
                   MIN(housing_type.basic_monthly_rent) AS monthly_rent_min,
                   MAX(housing_type.basic_monthly_rent) AS monthly_rent_max
            """;

    private static final String AREA_RANGE_QUERY = """
            , area_range AS (
                SELECT housing_type.housing_complex_id,
            """ + AREA_RANGE_COLUMNS + """
                FROM housing_types housing_type
                WHERE housing_type.admin_deleted = false
                GROUP BY housing_type.housing_complex_id
            )
            """;

    private static final String AREA_RANGE_JOIN = """
            LEFT JOIN area_range ON area_range.housing_complex_id = housing_complex.id
            """;

    private static final String MAP_AREA_RANGE_JOIN = """
            LEFT JOIN LATERAL (
                SELECT
            """ + AREA_RANGE_COLUMNS + """
                FROM housing_types housing_type
                WHERE housing_type.housing_complex_id = housing_complex.id AND housing_type.admin_deleted = false
            ) area_range ON TRUE
            """;

    private static final String SUMMARY_QUERY = """
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
                   area_range.deposit_min,
                   area_range.deposit_max,
                   area_range.monthly_rent_min,
                   area_range.monthly_rent_max,
                   representative.announcement_id,
                   representative.publication_type,
                   representative.posted_date,
                   housing_complex.completion_date,
                   %s AS application_status,
                   %s
            FROM %s housing_complex
            """
            + HousingComplexRepresentativeSql.LEFT_JOIN
            + ApplicationScheduleSql.displayPeriodJoin("representative", "housing_complex.id") + """
            %s
            WHERE housing_complex.admin_deleted = false
            """;

    private static final String BASE_SUMMARY_QUERY = HousingComplexRepresentativeSql.WITH_CLAUSE
            + AREA_RANGE_QUERY + summaryQuery("housing_complexes", AREA_RANGE_JOIN);

    private static final String MAP_SUMMARY_QUERY = HousingComplexRepresentativeSql.WITH_CLAUSE
            + VIEWPORT_QUERY + summaryQuery("viewport_complexes", MAP_AREA_RANGE_JOIN);

    private static final String MAP_ORDER = """
            ORDER BY housing_complex.id
            """;

    private static final Map<Direction, String> COMPARISON_OPERATORS = comparisonOperators();

    private static final Map<ComplexSort, SortSpec> SORT_SPECS = sortSpecs();

    private final HousingComplexFilterPredicateBuilder filterPredicateBuilder;

    ComplexSummarySqlBuilder(HousingComplexFilterPredicateBuilder filterPredicateBuilder) {
        this.filterPredicateBuilder = filterPredicateBuilder;
    }

    ComplexSummarySqlQuery buildMapQuery(HousingComplexSearchCondition condition) {
        String baseQuery = BASE_SUMMARY_QUERY;
        if (condition.bounds() != null) {
            baseQuery = MAP_SUMMARY_QUERY;
        }
        FilteredSummaryQuery query = filteredQuery(condition, baseQuery);
        return new ComplexSummarySqlQuery(query.sql() + MAP_ORDER, query.parameters());
    }

    ComplexSummarySqlQuery buildListQuery(
            HousingComplexSearchCondition condition,
            ComplexSort sort,
            ComplexSummaryCursor cursor,
            int limit
    ) {
        FilteredSummaryQuery query = filteredQuery(condition,
                BASE_SUMMARY_QUERY + boundsPredicate(condition.bounds()));
        Map<String, Object> parameters = new HashMap<>(query.parameters());
        parameters.put("limit", limit);
        return pageQuery(query.sql(), parameters, sortSpec(sort), cursor);
    }

    ComplexSummarySqlQuery buildCountQuery(HousingComplexSearchCondition condition) {
        FilteredSummaryQuery query = filteredQuery(condition,
                BASE_SUMMARY_QUERY + boundsPredicate(condition.bounds()));
        return new ComplexSummarySqlQuery(
                "SELECT COUNT(*) FROM (" + query.sql() + ") matching_complexes", query.parameters());
    }

    private static String summaryQuery(String complexTable, String areaRangeJoin) {
        return SUMMARY_QUERY.formatted(
                ApplicationScheduleSql.status("representative", "housing_complex.id"),
                ApplicationScheduleSql.displayPeriodColumns("representative"), complexTable, areaRangeJoin);
    }

    private String boundsPredicate(MapBounds bounds) {
        if (bounds == null) {
            return "";
        }
        return BOUNDS_PREDICATE;
    }

    private FilteredSummaryQuery filteredQuery(HousingComplexSearchCondition condition, String baseQuery) {
        HousingComplexFilterPredicate predicate = filterPredicateBuilder.build(condition.filters());
        Map<String, Object> parameters = new HashMap<>(boundsParameters(condition.bounds()));
        parameters.putAll(predicate.parameters());
        parameters.put("today", condition.filters().today());
        return new FilteredSummaryQuery(baseQuery + predicate.sql(), parameters);
    }

    private ComplexSummarySqlQuery pageQuery(
            String sql,
            Map<String, Object> parameters,
            SortSpec sortSpec,
            ComplexSummaryCursor cursor
    ) {
        if (cursor == null) {
            return new ComplexSummarySqlQuery(sql + firstPage(sortSpec), parameters);
        }
        parameters.put("cursorComplexId", cursor.complexId());
        if (cursor.primaryValue() == null) {
            return new ComplexSummarySqlQuery(sql + pageAfterNull(sortSpec), parameters);
        }
        parameters.put("cursorValue", cursor.primaryValue().jdbcValue());
        return new ComplexSummarySqlQuery(sql + pageAfterValue(sortSpec), parameters);
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
        return COMPARISON_OPERATORS.get(direction);
    }

    private String orderBy(SortSpec sortSpec) {
        return "ORDER BY " + sortSpec.expression() + " " + sortSpec.direction()
                + " NULLS LAST, housing_complex.id DESC\n";
    }

    private SortSpec sortSpec(ComplexSort sort) {
        return SORT_SPECS.get(sort);
    }

    private Map<String, Object> boundsParameters(MapBounds bounds) {
        if (bounds == null) {
            return Map.of();
        }
        return Map.of(
                "southWestLat", bounds.southWestLat(),
                "southWestLng", bounds.southWestLng(),
                "northEastLat", bounds.northEastLat(),
                "northEastLng", bounds.northEastLng()
        );
    }

    private static Map<Direction, String> comparisonOperators() {
        Map<Direction, String> operators = Map.of(
                Direction.ASC, ">",
                Direction.DESC, "<"
        );
        requireCompleteDirections(operators);
        return operators;
    }

    private static void requireCompleteDirections(Map<Direction, String> operators) {
        if (operators.keySet().equals(EnumSet.allOf(Direction.class))) {
            return;
        }
        throw new IllegalStateException("모든 정렬 방향의 비교 연산자가 필요합니다.");
    }

    private static Map<ComplexSort, SortSpec> sortSpecs() {
        Map<ComplexSort, SortSpec> specifications = Map.of(
                ComplexSort.LATEST_ANNOUNCEMENT, new SortSpec("representative.posted_date", Direction.DESC),
                ComplexSort.DEPOSIT_ASC, new SortSpec("area_range.deposit_min", Direction.ASC),
                ComplexSort.MONTHLY_RENT_ASC, new SortSpec("area_range.monthly_rent_min", Direction.ASC),
                ComplexSort.AREA_DESC, new SortSpec("area_range.exclusive_area_max", Direction.DESC),
                ComplexSort.COMPLETION_DATE_DESC, new SortSpec("housing_complex.completion_date", Direction.DESC)
        );
        requireCompleteSortSpecifications(specifications);
        return specifications;
    }

    private static void requireCompleteSortSpecifications(Map<ComplexSort, SortSpec> specifications) {
        if (specifications.keySet().equals(EnumSet.allOf(ComplexSort.class))) {
            return;
        }
        throw new IllegalStateException("모든 단지 정렬의 SQL 명세가 필요합니다.");
    }

    private enum Direction {
        ASC,
        DESC
    }

    private record SortSpec(String expression, Direction direction) {
    }

    private record FilteredSummaryQuery(String sql, Map<String, Object> parameters) {
    }
}
