package com.toadzip.backend.search.repository;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.global.persistence.LegacyStoredValue;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.search.domain.SearchType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InternalSearchRepository {

    private static final String PROVIDER_NAME = """
            CASE announcement.provider
                WHEN 'LH' THEN '한국토지주택공사'
                WHEN 'SH' THEN '서울주택도시공사'
                WHEN 'GH' THEN '경기주택도시공사'
                ELSE announcement.provider
            END
            """.strip();

    private static final String LATEST_LEAF = """
            NOT EXISTS (
                SELECT 1 FROM announcements successor
                WHERE successor.previous_announcement_id = announcement.id
            )
            """;

    private final JdbcClient jdbcClient;

    public InternalSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SearchSourceItem> findAnnouncements(IntegratedSearchCondition condition, int limit) {
        Map<String, Object> parameters = baseParameters(condition, limit);
        StringBuilder sql = new StringBuilder("""
                SELECT announcement.id,
                       announcement.name,
                       announcement.provider,
                       announcement.posted_date,
                       announcement.application_start_date,
                       announcement.application_end_date,
                       announcement.status,
                       CASE
                           WHEN announcement.status IN ('CANCELLATION', '취소공고') THEN 'CANCELLED'
                           WHEN announcement.application_start_date > :today THEN 'BEFORE_APPLICATION'
                           WHEN announcement.application_end_date < :today THEN 'CLOSED'
                           ELSE 'APPLYING'
                       END AS application_status,
                       COALESCE((
                           SELECT STRING_AGG(DISTINCT complex.road_address, ' ')
                           FROM supply_rows supply_row
                           JOIN housing_complexes complex ON complex.id = supply_row.housing_complex_id
                           WHERE supply_row.announcement_id = announcement.id
                       ), '') AS region_names,
                       (
                           SELECT complex.latitude
                           FROM supply_rows supply_row
                           JOIN housing_complexes complex ON complex.id = supply_row.housing_complex_id
                           WHERE supply_row.announcement_id = announcement.id
                             AND complex.latitude IS NOT NULL
                             AND complex.longitude IS NOT NULL
                           ORDER BY supply_row.display_order, complex.id
                           LIMIT 1
                       ) AS latitude,
                       (
                           SELECT complex.longitude
                           FROM supply_rows supply_row
                           JOIN housing_complexes complex ON complex.id = supply_row.housing_complex_id
                           WHERE supply_row.announcement_id = announcement.id
                             AND complex.latitude IS NOT NULL
                             AND complex.longitude IS NOT NULL
                           ORDER BY supply_row.display_order, complex.id
                           LIMIT 1
                       ) AS longitude
                FROM announcements announcement
                WHERE
                """).append(LATEST_LEAF);
        addAnnouncementTokens(sql, parameters, condition);
        addRentalTypes(sql, parameters, "announcement.supply_type", condition);
        addAnnouncementStatuses(sql, parameters, condition);
        addAnnouncementOrder(sql);
        sql.append(", announcement.posted_date DESC, announcement.id DESC LIMIT :limit");
        return jdbcClient.sql(sql.toString()).params(parameters).query(this::mapAnnouncement).list();
    }

    public List<SearchSourceItem> findComplexes(IntegratedSearchCondition condition, int limit) {
        Map<String, Object> parameters = baseParameters(condition, limit);
        StringBuilder sql = new StringBuilder("""
                SELECT complex.id,
                       complex.name,
                       complex.road_address,
                       complex.latitude,
                       complex.longitude,
                       complex.city_county_district_code,
                       complex.supply_type
                FROM housing_complexes complex
                WHERE 1 = 1
                """);
        addComplexTokens(sql, parameters, condition);
        addRentalTypes(sql, parameters, "complex.supply_type", condition);
        addComplexAnnouncementFilter(sql, parameters, condition);
        sql.append("""
                 ORDER BY CASE
                     WHEN LOWER(complex.name) = :exactQuery
                       OR LOWER(complex.road_address) = :exactQuery THEN 0
                     WHEN LOWER(complex.name) LIKE :prefixQuery ESCAPE '\\'
                       OR LOWER(complex.road_address) LIKE :prefixQuery ESCAPE '\\' THEN 1
                     ELSE 2
                 END, complex.name ASC, complex.id ASC LIMIT :limit
                """);
        return jdbcClient.sql(sql.toString()).params(parameters).query(this::mapComplex).list();
    }

    private Map<String, Object> baseParameters(IntegratedSearchCondition condition, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("today", condition.today());
        parameters.put("limit", limit);
        parameters.put("exactQuery", condition.match().normalizedQuery().toLowerCase(java.util.Locale.ROOT));
        parameters.put("prefixQuery", prefixLike(condition.match().normalizedQuery()));
        return parameters;
    }

    private void addAnnouncementTokens(
            StringBuilder sql,
            Map<String, Object> parameters,
            IntegratedSearchCondition condition
    ) {
        for (int index = 0; index < condition.match().tokens().size(); index++) {
            String parameter = "token" + index;
            sql.append("""
                     AND (
                         LOWER(announcement.name) LIKE :%s ESCAPE '\\'
                         OR LOWER(%s) LIKE :%s ESCAPE '\\'
                         OR EXISTS (
                             SELECT 1 FROM supply_rows matched_row
                             JOIN housing_complexes matched_complex
                               ON matched_complex.id = matched_row.housing_complex_id
                             WHERE matched_row.announcement_id = announcement.id
                               AND LOWER(matched_complex.road_address) LIKE :%s ESCAPE '\\'
                         )
                     )
                    """.formatted(parameter, PROVIDER_NAME, parameter, parameter));
            parameters.put(parameter, like(condition.match().tokens().get(index)));
        }
    }

    private void addComplexTokens(
            StringBuilder sql,
            Map<String, Object> parameters,
            IntegratedSearchCondition condition
    ) {
        for (int index = 0; index < condition.match().tokens().size(); index++) {
            String parameter = "token" + index;
            sql.append(" AND (LOWER(complex.name) LIKE :")
                    .append(parameter)
                    .append(" ESCAPE '\\' OR LOWER(complex.road_address) LIKE :")
                    .append(parameter)
                    .append(" ESCAPE '\\')");
            parameters.put(parameter, like(condition.match().tokens().get(index)));
        }
    }

    private void addRentalTypes(
            StringBuilder sql,
            Map<String, Object> parameters,
            String column,
            IntegratedSearchCondition condition
    ) {
        if (condition.rentalTypes().isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (:rentalTypes)");
        parameters.put("rentalTypes", storedValues(condition.rentalTypes()));
    }

    private void addAnnouncementStatuses(
            StringBuilder sql,
            Map<String, Object> parameters,
            IntegratedSearchCondition condition
    ) {
        if (condition.applicationStatuses().isEmpty()) {
            return;
        }
        StringJoiner filters = new StringJoiner(" OR ", " AND (", ")");
        condition.applicationStatuses().forEach(status -> filters.add(announcementStatus(status)));
        sql.append(filters);
    }

    private String announcementStatus(ApplicationStatus status) {
        return switch (status) {
            case BEFORE_APPLICATION -> "announcement.status NOT IN ('CANCELLATION', '취소공고')"
                    + " AND announcement.application_start_date > :today";
            case APPLYING -> "announcement.status NOT IN ('CANCELLATION', '취소공고')"
                    + " AND announcement.application_start_date <= :today"
                    + " AND announcement.application_end_date >= :today";
            case CLOSED -> "announcement.status NOT IN ('CANCELLATION', '취소공고')"
                    + " AND announcement.application_end_date < :today";
            case CANCELLED -> "announcement.status IN ('CANCELLATION', '취소공고')";
        };
    }

    private void addComplexAnnouncementFilter(
            StringBuilder sql,
            Map<String, Object> parameters,
            IntegratedSearchCondition condition
    ) {
        if (condition.hasActiveAnnouncement() == null && condition.applicationStatuses().isEmpty()) {
            return;
        }
        String active = activeAnnouncementExists();
        if (Boolean.TRUE.equals(condition.hasActiveAnnouncement())) {
            sql.append(" AND EXISTS (").append(active).append(")");
        }
        if (Boolean.FALSE.equals(condition.hasActiveAnnouncement())) {
            sql.append(" AND NOT EXISTS (").append(active).append(")");
        }
        if (!condition.applicationStatuses().isEmpty()) {
            StringJoiner filters = new StringJoiner(" OR ", " AND (", ")");
            condition.applicationStatuses().stream()
                    .filter(status -> status != ApplicationStatus.CANCELLED)
                    .map(status -> "EXISTS (" + statusAnnouncementStart()
                            + " AND announcement.status NOT IN ('CANCELLATION', '취소공고')"
                            + " AND " + complexStatus(status) + ")")
                    .forEach(filters::add);
            if (condition.applicationStatuses().contains(ApplicationStatus.CANCELLED)) {
                filters.add("EXISTS (" + statusAnnouncementStart()
                        + " AND announcement.status IN ('CANCELLATION', '취소공고'))");
            }
            sql.append(filters);
        }
    }

    private String activeAnnouncementExists() {
        return statusAnnouncementStart()
                + " AND announcement.status NOT IN ('CANCELLATION', '취소공고')"
                + " AND announcement.application_start_date <= :today"
                + " AND announcement.application_end_date >= :today";
    }

    private String statusAnnouncementStart() {
        return "SELECT 1 FROM supply_rows supply_row JOIN announcements announcement"
                + " ON announcement.id = supply_row.announcement_id"
                + " WHERE supply_row.housing_complex_id = complex.id AND " + LATEST_LEAF;
    }

    private String complexStatus(ApplicationStatus status) {
        return switch (status) {
            case BEFORE_APPLICATION -> "announcement.application_start_date > :today";
            case APPLYING -> "announcement.application_start_date <= :today"
                    + " AND announcement.application_end_date >= :today";
            case CLOSED -> "announcement.application_end_date < :today";
            case CANCELLED -> "1 = 0";
        };
    }

    private SearchSourceItem mapAnnouncement(ResultSet resultSet, int rowNumber) throws SQLException {
        String publicationType = resultSet.getString("status");
        boolean cancelled = "CANCELLATION".equals(publicationType) || "취소공고".equals(publicationType);
        return new SearchSourceItem(
                SearchType.ANNOUNCEMENT,
                resultSet.getString("id"),
                resultSet.getString("name"),
                providerName(resultSet.getString("provider")),
                resultSet.getString("region_names"),
                "공고",
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getObject("posted_date", java.time.LocalDate.class),
                resultSet.getString("application_status"),
                cancelled,
                null
        );
    }

    private SearchSourceItem mapComplex(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SearchSourceItem(
                SearchType.COMPLEX,
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("road_address"),
                resultSet.getString("road_address"),
                "단지",
                resultSet.getBigDecimal("latitude"),
                resultSet.getBigDecimal("longitude"),
                null,
                null,
                false,
                resultSet.getString("city_county_district_code")
        );
    }

    private String providerName(String provider) {
        try {
            return AgencyCode.fromStoredValue(provider).displayName();
        } catch (IllegalArgumentException exception) {
            return provider;
        }
    }

    private Set<String> storedValues(Set<? extends LegacyStoredValue> values) {
        return values.stream()
                .flatMap(value -> value.storedValues().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String like(String token) {
        return "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String prefixLike(String query) {
        return query.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private void addAnnouncementOrder(StringBuilder sql) {
        sql.append(" ORDER BY CASE WHEN LOWER(announcement.name) = :exactQuery")
                .append(" OR LOWER(").append(PROVIDER_NAME).append(") = :exactQuery")
                .append(" OR EXISTS (SELECT 1 FROM supply_rows exact_row")
                .append(" JOIN housing_complexes exact_complex ON exact_complex.id = exact_row.housing_complex_id")
                .append(" WHERE exact_row.announcement_id = announcement.id")
                .append(" AND LOWER(exact_complex.road_address) = :exactQuery) THEN 0")
                .append(" WHEN LOWER(announcement.name) LIKE :prefixQuery ESCAPE '\\'")
                .append(" OR LOWER(").append(PROVIDER_NAME).append(") LIKE :prefixQuery ESCAPE '\\'")
                .append(" OR EXISTS (SELECT 1 FROM supply_rows prefix_row")
                .append(" JOIN housing_complexes prefix_complex ON prefix_complex.id = prefix_row.housing_complex_id")
                .append(" WHERE prefix_row.announcement_id = announcement.id")
                .append(" AND LOWER(prefix_complex.road_address) LIKE :prefixQuery ESCAPE '\\') THEN 1")
                .append(" ELSE 2 END");
    }
}
