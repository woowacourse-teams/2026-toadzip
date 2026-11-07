package com.toadzip.backend.housing.repository;

import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;
import com.toadzip.backend.housing.domain.ComplexSort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ComplexSummaryQueryRepository {

    private static final String ADMIN_RECRUITING = """
            EXISTS (SELECT 1 FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
                WHERE r.housing_complex_id = h.id AND r.admin_excluded = false
                  AND a.admin_deleted = false AND a.release_status = 'PUBLISHED'
                  AND NOT EXISTS (SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = a.id AND successor.admin_deleted = false
                      AND successor.release_status = 'PUBLISHED')
                  AND %s IN ('BEFORE_APPLICATION', 'APPLYING'))
            """.formatted(ApplicationScheduleSql.status("a", "h.id"));
    private static final String ADMIN_COUNTS_SQL = """
            SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE %s) AS recruiting,
                COUNT(*) FILTER (WHERE h.latitude IS NULL OR h.longitude IS NULL) AS missing_coordinates,
                COUNT(*) FILTER (WHERE h.source_review_required) AS review_required,
                COUNT(*) FILTER (WHERE h.provider IN ('LH', '한국토지주택공사')) AS lh,
                COUNT(*) FILTER (WHERE h.provider IN ('SH', '서울주택도시공사')) AS sh,
                COUNT(*) FILTER (WHERE h.provider IN ('GH', '경기주택도시공사')) AS gh
            FROM housing_complexes h WHERE h.admin_deleted = false
            """.formatted(ADMIN_RECRUITING);

    private final JdbcClient jdbcClient;

    private final ComplexSummarySqlBuilder sqlBuilder;

    public ComplexSummaryQueryRepository(JdbcClient jdbcClient, ComplexSummarySqlBuilder sqlBuilder) {
        this.jdbcClient = jdbcClient;
        this.sqlBuilder = sqlBuilder;
    }

    public List<ComplexSummaryRow> findAll(HousingComplexSearchCondition condition) {
        ComplexSummarySqlQuery query = sqlBuilder.buildMapQuery(condition);
        return execute(query);
    }

    public List<ComplexSummaryRow> findPage(
            HousingComplexSearchCondition condition,
            ComplexSort sort,
            ComplexSummaryCursor cursor,
            int limit
    ) {
        ComplexSummarySqlQuery query = sqlBuilder.buildListQuery(condition, sort, cursor, limit);
        return execute(query);
    }

    public long count(HousingComplexSearchCondition condition) {
        ComplexSummarySqlQuery query = sqlBuilder.buildCountQuery(condition);
        return jdbcClient.sql(query.sql()).params(query.parameters()).query(Long.class).single();
    }

    public AdminCounts countForAdmin(LocalDate today) {
        return jdbcClient.sql(ADMIN_COUNTS_SQL).param("today", today).query((row, index) -> new AdminCounts(
                row.getLong("total"), row.getLong("recruiting"), row.getLong("missing_coordinates"),
                row.getLong("review_required"), row.getLong("lh"), row.getLong("sh"), row.getLong("gh"))).single();
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
                resultSet.getObject("application_end_date", java.time.LocalDate.class),
                resultSet.getObject("completion_date", java.time.LocalDate.class),
                resultSet.getString("application_status"),
                resultSet.getObject("confirmed_application_end_date", java.time.LocalDate.class)
        );
    }

    private List<ComplexSummaryRow> execute(ComplexSummarySqlQuery query) {
        return jdbcClient.sql(query.sql())
                .params(query.parameters())
                .query(this::mapRow)
                .list();
    }

    public record AdminCounts(long total, long recruiting, long missingCoordinates, long reviewRequired,
            long lh, long sh, long gh) {
    }
}
