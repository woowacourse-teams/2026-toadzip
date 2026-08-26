package com.toadzip.backend.housing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.housing.domain.MapBounds;

@Repository
public class ComplexSummaryQueryRepository {

    private static final String FIND_ALL_IN_BOUNDS = """
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
            ORDER BY housing_complex.id
            """;

    private final JdbcClient jdbcClient;

    public ComplexSummaryQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ComplexSummaryRow> findAllInBounds(MapBounds bounds) {
        return jdbcClient.sql(FIND_ALL_IN_BOUNDS)
                .param("southWestLat", bounds.southWestLat())
                .param("southWestLng", bounds.southWestLng())
                .param("northEastLat", bounds.northEastLat())
                .param("northEastLng", bounds.northEastLng())
                .query(this::mapRow)
                .list();
    }

    private ComplexSummaryRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ComplexSummaryRow(
                resultSet.getLong("complex_id"),
                resultSet.getString("name"),
                resultSet.getString("image_url"),
                resultSet.getString("province_code"),
                resultSet.getString("city_county_district_code"),
                resultSet.getString("rental_type"),
                resultSet.getString("agency_code"),
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getBigDecimal("exclusive_area_min"),
                resultSet.getBigDecimal("exclusive_area_max"),
                resultSet.getBigDecimal("deposit_min"),
                resultSet.getBigDecimal("deposit_max"),
                resultSet.getBigDecimal("monthly_rent_min"),
                resultSet.getBigDecimal("monthly_rent_max"),
                resultSet.getObject("announcement_id", Long.class),
                resultSet.getString("publication_type"),
                resultSet.getObject("posted_date", java.time.LocalDate.class),
                resultSet.getObject("application_start_date", java.time.LocalDate.class),
                resultSet.getObject("application_end_date", java.time.LocalDate.class)
        );
    }
}
