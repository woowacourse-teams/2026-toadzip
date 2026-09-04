package com.toadzip.backend.housing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.housing.domain.ComplexSort;

@Repository
public class ComplexSummaryQueryRepository {

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
                resultSet.getObject("completion_date", java.time.LocalDate.class)
        );
    }

    private List<ComplexSummaryRow> execute(ComplexSummarySqlQuery query) {
        return jdbcClient.sql(query.sql())
                .params(query.parameters())
                .query(this::mapRow)
                .list();
    }
}
