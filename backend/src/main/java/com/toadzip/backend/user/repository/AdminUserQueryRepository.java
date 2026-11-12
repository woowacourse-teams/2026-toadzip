package com.toadzip.backend.user.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.user.dto.AdminUserProvider;
import com.toadzip.backend.user.dto.AdminUserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserQueryRepository {

    private static final String MEMBER_SELECT = """
            SELECT id, email, created_at, admin,
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

    public List<DailyRegistrations> countRegistrationsByDay(LocalDate start, LocalDate end, LocalDateTime asOf) {
        return jdbc.sql("""
                SELECT day::date AS day, COUNT(member.id) AS count
                FROM generate_series(CAST(:start AS date), CAST(:end AS date), INTERVAL '1 day') day
                LEFT JOIN users member ON member.created_at >= day AND member.created_at < day + INTERVAL '1 day'
                    AND member.created_at <= :asOf
                GROUP BY day ORDER BY day
                """).param("start", start).param("end", end).param("asOf", asOf)
                .query((row, index) -> new DailyRegistrations(row.getObject("day", LocalDate.class),
                        row.getLong("count"))).list();
    }

    /** 가입 시각이 [start, end) 구간에 속하는 회원을 센다. */
    public long countRegisteredBetween(LocalDateTime start, LocalDateTime end) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE created_at >= :start AND created_at < :end")
                .param("start", start).param("end", end).query(Long.class).single();
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
                AdminUserProvider.valueOf(row.getString("provider")), row.getObject("created_at", LocalDateTime.class),
                row.getBoolean("admin"));
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

    public record DailyRegistrations(LocalDate day, long count) {
    }
}
