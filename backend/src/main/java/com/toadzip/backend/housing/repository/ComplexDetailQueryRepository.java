package com.toadzip.backend.housing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ComplexDetailQueryRepository {

    private static final String FIND_COMPLEX = """
            SELECT housing_complex.id AS complex_id,
                   housing_complex.name,
                   housing_complex.image_url,
                   housing_complex.province_code,
                   housing_complex.city_county_district_code,
                   housing_complex.road_address,
                   housing_complex.supply_type AS rental_type,
                   housing_complex.provider AS agency_code,
                   housing_complex.latitude,
                   housing_complex.longitude,
                   housing_complex.completion_date,
                   housing_complex.housing_type AS building_type,
                   housing_complex.elevator_installed AS has_elevator,
                   housing_complex.heating_type,
                   housing_complex.corridor_type,
                   housing_complex.recent_one_year_move_out_count AS move_out_count_last_year,
                   housing_complex.total_household_count,
                   housing_complex.parking_space_count AS total_parking_count
            FROM housing_complexes housing_complex
            WHERE housing_complex.id = :complexId
            """;

    private static final String FIND_HOUSING_TYPES = """
            SELECT housing_type.id AS housing_type_id,
                   housing_type.name,
                   housing_type.exclusive_area,
                   housing_type.supply_area,
                   housing_type.floor_plan_url AS floor_plan_image_url,
                   housing_type.duplex AS is_duplex,
                   housing_type.maintenance_fee
            FROM housing_types housing_type
            WHERE housing_type.housing_complex_id = :complexId
            ORDER BY housing_type.id
            """;

    private static final String CURRENT_LEAF_CTE = """
            WITH current_leaf AS (
                SELECT announcement.*
                FROM announcements announcement
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM announcements successor
                    WHERE successor.previous_announcement_id = announcement.id
                )
                  AND announcement.status NOT IN ('CANCELLATION', '취소공고')
                  AND announcement.application_end_date >= :today
            )
            """;

    private static final String FIND_CURRENT_SUPPLY_CONDITIONS = CURRENT_LEAF_CTE + """
            SELECT announcement.id AS announcement_id,
                   supply_row.id AS supply_row_id,
                   housing_type.id AS housing_type_id,
                   supply_target.id AS target_id,
                   supply_target.target,
                   supply_target.rental_deposit AS deposit,
                   supply_target.monthly_rent,
                   supply_target.converted_deposit AS convertible_deposit
            FROM current_leaf announcement
            JOIN supply_rows supply_row
              ON supply_row.announcement_id = announcement.id
             AND supply_row.housing_complex_id = :complexId
            JOIN housing_types housing_type
              ON housing_type.id = supply_row.housing_type_id
             AND housing_type.housing_complex_id = :complexId
            JOIN supply_targets supply_target ON supply_target.supply_row_id = supply_row.id
            ORDER BY announcement.posted_date DESC,
                     announcement.id DESC,
                     supply_row.display_order,
                     supply_row.id,
                     supply_target.display_order,
                     supply_target.id
            """;

    private static final String FIND_CURRENT_ANNOUNCEMENTS = CURRENT_LEAF_CTE + """
            SELECT announcement.id AS announcement_id,
                   announcement.name AS title,
                   announcement.status AS publication_type,
                   announcement.posted_date,
                   announcement.application_start_date,
                   announcement.application_end_date
            FROM current_leaf announcement
            WHERE EXISTS (
                SELECT 1
                FROM supply_rows supply_row
                WHERE supply_row.announcement_id = announcement.id
                  AND supply_row.housing_complex_id = :complexId
            )
            ORDER BY announcement.posted_date DESC, announcement.id DESC
            """;

    private static final String FIND_CURRENT_ANNOUNCEMENT_TARGETS = CURRENT_LEAF_CTE + """
            , ranked_target AS (
                SELECT announcement.id AS announcement_id,
                       announcement.posted_date,
                       supply_row.id AS supply_row_id,
                       supply_row.display_order AS supply_row_display_order,
                       supply_target.id AS target_id,
                       supply_target.target,
                       supply_target.display_order AS target_display_order,
                       ROW_NUMBER() OVER (
                           PARTITION BY announcement.id, supply_target.target
                           ORDER BY supply_row.display_order,
                                    supply_row.id,
                                    supply_target.display_order,
                                    supply_target.id
                       ) AS target_rank
                FROM current_leaf announcement
                JOIN supply_rows supply_row
                  ON supply_row.announcement_id = announcement.id
                 AND supply_row.housing_complex_id = :complexId
                JOIN supply_targets supply_target ON supply_target.supply_row_id = supply_row.id
            )
            SELECT announcement_id,
                   supply_row_id,
                   target_id,
                   target
            FROM ranked_target
            WHERE target_rank = 1
            ORDER BY posted_date DESC,
                     announcement_id DESC,
                     supply_row_display_order,
                     supply_row_id,
                     target_display_order,
                     target_id
            """;

    private final JdbcClient jdbcClient;

    public ComplexDetailQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<ComplexDetailRow> findComplex(long complexId) {
        return jdbcClient.sql(FIND_COMPLEX)
                .param("complexId", complexId)
                .query(this::mapComplex)
                .optional();
    }

    public List<HousingTypeDetailRow> findHousingTypes(long complexId) {
        return jdbcClient.sql(FIND_HOUSING_TYPES)
                .param("complexId", complexId)
                .query(this::mapHousingType)
                .list();
    }

    public List<CurrentSupplyConditionRow> findCurrentSupplyConditions(long complexId, LocalDate today) {
        return jdbcClient.sql(FIND_CURRENT_SUPPLY_CONDITIONS)
                .param("complexId", complexId)
                .param("today", today)
                .query(this::mapSupplyCondition)
                .list();
    }

    public List<CurrentAnnouncementRow> findCurrentAnnouncements(long complexId, LocalDate today) {
        return jdbcClient.sql(FIND_CURRENT_ANNOUNCEMENTS)
                .param("complexId", complexId)
                .param("today", today)
                .query(this::mapAnnouncement)
                .list();
    }

    public List<CurrentAnnouncementTargetRow> findCurrentAnnouncementTargets(long complexId, LocalDate today) {
        return jdbcClient.sql(FIND_CURRENT_ANNOUNCEMENT_TARGETS)
                .param("complexId", complexId)
                .param("today", today)
                .query(this::mapAnnouncementTarget)
                .list();
    }

    private ComplexDetailRow mapComplex(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ComplexDetailRow(
                resultSet.getLong("complex_id"),
                resultSet.getString("name"),
                resultSet.getString("image_url"),
                resultSet.getString("province_code"),
                resultSet.getString("city_county_district_code"),
                resultSet.getString("road_address"),
                resultSet.getString("rental_type"),
                resultSet.getString("agency_code"),
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getObject("completion_date", LocalDate.class),
                resultSet.getString("building_type"),
                resultSet.getBoolean("has_elevator"),
                resultSet.getString("heating_type"),
                resultSet.getString("corridor_type"),
                resultSet.getObject("move_out_count_last_year", Integer.class),
                resultSet.getInt("total_household_count"),
                resultSet.getInt("total_parking_count")
        );
    }

    private HousingTypeDetailRow mapHousingType(ResultSet resultSet, int rowNumber) throws SQLException {
        return new HousingTypeDetailRow(
                resultSet.getLong("housing_type_id"),
                resultSet.getString("name"),
                resultSet.getBigDecimal("exclusive_area"),
                resultSet.getBigDecimal("supply_area"),
                resultSet.getString("floor_plan_image_url"),
                resultSet.getBoolean("is_duplex"),
                resultSet.getBigDecimal("maintenance_fee")
        );
    }

    private CurrentSupplyConditionRow mapSupplyCondition(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new CurrentSupplyConditionRow(
                resultSet.getLong("announcement_id"),
                resultSet.getLong("supply_row_id"),
                resultSet.getLong("housing_type_id"),
                resultSet.getLong("target_id"),
                resultSet.getString("target"),
                resultSet.getBigDecimal("deposit"),
                resultSet.getBigDecimal("monthly_rent"),
                resultSet.getBigDecimal("convertible_deposit")
        );
    }

    private CurrentAnnouncementRow mapAnnouncement(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CurrentAnnouncementRow(
                resultSet.getLong("announcement_id"),
                resultSet.getString("title"),
                resultSet.getString("publication_type"),
                resultSet.getObject("posted_date", LocalDate.class),
                resultSet.getObject("application_start_date", LocalDate.class),
                resultSet.getObject("application_end_date", LocalDate.class)
        );
    }

    private CurrentAnnouncementTargetRow mapAnnouncementTarget(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new CurrentAnnouncementTargetRow(
                resultSet.getLong("announcement_id"),
                resultSet.getLong("supply_row_id"),
                resultSet.getLong("target_id"),
                resultSet.getString("target")
        );
    }
}
