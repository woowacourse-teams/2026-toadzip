package com.toadzip.backend.housing.repository;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.DateValue;

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
                   representative.application_end_date
            FROM housing_complexes housing_complex
            LEFT JOIN representative ON representative.housing_complex_id = housing_complex.id
            LEFT JOIN area_range ON area_range.housing_complex_id = housing_complex.id
            LEFT JOIN price_range ON price_range.housing_complex_id = housing_complex.id
            WHERE housing_complex.latitude BETWEEN :southWestLat AND :northEastLat
              AND housing_complex.longitude BETWEEN :southWestLng AND :northEastLng
            """;

    private static final String MAP_ORDER = """
            ORDER BY housing_complex.id
            """;

    private static final String FIRST_PAGE = """
            ORDER BY representative.posted_date DESC NULLS LAST, housing_complex.id DESC
            LIMIT :limit
            """;

    private static final String PAGE_AFTER_POSTED_DATE = """
              AND (
                    representative.posted_date < :cursorPostedDate
                    OR (
                        representative.posted_date = :cursorPostedDate
                        AND housing_complex.id < :cursorComplexId
                    )
                    OR representative.posted_date IS NULL
              )
            ORDER BY representative.posted_date DESC NULLS LAST, housing_complex.id DESC
            LIMIT :limit
            """;

    private static final String PAGE_AFTER_NULL_DATE = """
              AND representative.posted_date IS NULL
              AND housing_complex.id < :cursorComplexId
            ORDER BY representative.posted_date DESC NULLS LAST, housing_complex.id DESC
            LIMIT :limit
            """;

    ComplexSummarySqlQuery buildMapQuery(HousingComplexSearchCondition condition) {
        return new ComplexSummarySqlQuery(
                BASE_SUMMARY_QUERY + MAP_ORDER,
                boundsParameters(condition.bounds())
        );
    }

    ComplexSummarySqlQuery buildListQuery(
            HousingComplexSearchCondition condition,
            ComplexSort sort,
            ComplexSummaryCursor cursor,
            int limit
    ) {
        Map<String, Object> parameters = new HashMap<>(boundsParameters(condition.bounds()));
        parameters.put("limit", limit);
        if (cursor == null) {
            return new ComplexSummarySqlQuery(BASE_SUMMARY_QUERY + FIRST_PAGE, parameters);
        }
        parameters.put("cursorComplexId", cursor.complexId());
        if (cursor.primaryValue() == null) {
            return new ComplexSummarySqlQuery(BASE_SUMMARY_QUERY + PAGE_AFTER_NULL_DATE, parameters);
        }
        DateValue postedDate = (DateValue) cursor.primaryValue();
        parameters.put("cursorPostedDate", postedDate.value());
        return new ComplexSummarySqlQuery(BASE_SUMMARY_QUERY + PAGE_AFTER_POSTED_DATE, parameters);
    }

    private Map<String, Object> boundsParameters(MapBounds bounds) {
        return Map.of(
                "southWestLat", bounds.southWestLat(),
                "southWestLng", bounds.southWestLng(),
                "northEastLat", bounds.northEastLat(),
                "northEastLng", bounds.northEastLng()
        );
    }
}
