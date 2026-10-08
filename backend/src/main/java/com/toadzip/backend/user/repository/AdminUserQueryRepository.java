package com.toadzip.backend.user.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.user.dto.AdminUserProvider;
import com.toadzip.backend.user.dto.AdminUserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserQueryRepository {

    private static final String MEMBER_SELECT = """
            SELECT id, email, created_at,
                CASE WHEN login_identifier LIKE 'google:%' THEN 'GOOGLE'
                     WHEN login_identifier LIKE 'kakao:%' THEN 'KAKAO'
                     ELSE 'UNKNOWN' END AS provider
            FROM users
            """;
    private static final String PAGE_SQL = """
            WITH members AS (%s), filtered AS (
                SELECT * FROM members
                WHERE (:keyword = '' OR LOWER(COALESCE(email, '')) LIKE :pattern ESCAPE '!'
                    OR id = CAST(:identifier AS bigint))
                    AND (:provider = '' OR provider = :provider)
            ), page_members AS (
                SELECT * FROM filtered
                ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
            )
            SELECT totals.total_elements, member.*
            FROM (SELECT COUNT(*) AS total_elements FROM filtered) totals
            LEFT JOIN page_members member ON TRUE
            ORDER BY member.created_at DESC, member.id DESC
            """.formatted(MEMBER_SELECT);

    private final JdbcClient jdbc;

    public AdminUserQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<AdminUserSummary> search(String keyword, AdminUserProvider provider, int page, int size) {
        List<MemberRow> rows = jdbc.sql(PAGE_SQL).param("keyword", keyword)
                .param("pattern", pattern(keyword)).param("identifier", numericIdentifier(keyword))
                .param("provider", providerName(provider)).param("size", size).param("offset", (long) page * size)
                .query((row, index) -> memberRow(row)).list();
        long total = rows.getFirst().total();
        int totalPages = Math.toIntExact((total + size - 1) / size);
        List<AdminUserSummary> items = rows.stream().map(MemberRow::member).filter(member -> member != null).toList();
        return new AdminPage<>(items, page, (long) page + 1 < totalPages, total, totalPages);
    }

    public Optional<AdminUserSummary> findById(long id) {
        return jdbc.sql(MEMBER_SELECT + " WHERE id = :id").param("id", id)
                .query((row, index) -> summary(row)).optional();
    }

    private MemberRow memberRow(ResultSet row) throws SQLException {
        if (row.getObject("id") == null) {
            return new MemberRow(row.getLong("total_elements"), null);
        }
        return new MemberRow(row.getLong("total_elements"), summary(row));
    }

    private AdminUserSummary summary(ResultSet row) throws SQLException {
        return new AdminUserSummary(row.getLong("id"), row.getString("email"),
                AdminUserProvider.valueOf(row.getString("provider")), row.getObject("created_at", LocalDateTime.class));
    }

    private String pattern(String keyword) {
        return "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private Long numericIdentifier(String keyword) {
        if (!keyword.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(keyword);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String providerName(AdminUserProvider provider) {
        if (provider == null) {
            return "";
        }
        return provider.name();
    }

    private record MemberRow(long total, AdminUserSummary member) {
    }
}
