package com.toadzip.backend.eligibility.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class EligibilityMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_데이터를_보존하며_초안_공개_중복과_불완전한_공개를_거부한다() throws Exception {
        inSchema(
                sql -> {
                    assertThat(count(sql, "announcement_rule_sets")).isOne();
                    assertThat(count(sql, "eligibility_profiles")).isOne();
                    sql.execute(
                            "INSERT INTO basic_comparison_rule_sets (announcement_id, content)"
                                + " VALUES (1, '{}')");
                    rejected(
                            sql,
                            "INSERT INTO basic_comparison_rule_sets (announcement_id, content)"
                                + " VALUES (1, '{}')",
                            "23505");
                    rejected(
                            sql,
                            "UPDATE basic_comparison_rule_sets SET status = 'PUBLISHED'",
                            "23514");
                    rejected(
                            sql,
                            "UPDATE basic_comparison_rule_sets SET schema_version = 1",
                            "23514");
                    sql.execute(
                            "UPDATE basic_comparison_rule_sets SET status = 'PUBLISHED', version ="
                                + " 1, published_at = now()");
                    sql.execute(
                            "INSERT INTO basic_comparison_rule_sets (announcement_id, content)"
                                + " VALUES (1, '{}')");
                    rejected(
                            sql,
                            "UPDATE basic_comparison_rule_sets SET status = 'PUBLISHED', version ="
                                + " 2, published_at = now() WHERE status = 'DRAFT'",
                            "23505");
                    assertThat(count(sql, "basic_comparison_rule_sets")).isEqualTo(2);
                });
    }

    @Test
    void 회원과_공고가_삭제되면_해당_현재_데이터만_지운다() throws Exception {
        inSchema(
                sql -> {
                    sql.execute(
                            "INSERT INTO basic_comparison_rule_sets (announcement_id, content)"
                                + " VALUES (1, '{}'), (2, '{}')");
                    sql.execute(
                            "INSERT INTO basic_comparison_profiles (user_id, version, profile)"
                                + " VALUES (1, 1, '{}'), (2, 1, '{}')");
                    rejected(
                            sql,
                            "UPDATE basic_comparison_profiles SET input_contract_version = 1",
                            "23514");
                    rejected(sql, "UPDATE basic_comparison_profiles SET version = 0", "23514");
                    sql.execute("DELETE FROM users WHERE id = 1");
                    sql.execute("DELETE FROM announcements WHERE id = 1");
                    assertThat(count(sql, "basic_comparison_profiles")).isOne();
                    assertThat(count(sql, "basic_comparison_rule_sets")).isOne();
                });
    }

    @Test
    void 기존_구조를_투영하고_다른_판본의_단위_경로_참조를_거부한다() throws Exception {
        inSchema(sql -> {
            sql.execute("""
                    INSERT INTO basic_comparison_rule_sets (announcement_id, content) VALUES
                    (1, '{"supplyUnits":[{"id":"one","label":"단위1","housingTypes":[]}],
                          "paths":[{"id":"path","label":"일반","kind":"GENERAL","checks":[]}],
                          "options":[{"id":"choice","supplyUnitId":"one","pathId":"path",
                                       "recruitmentStatus":"RECRUITING"}]}'),
                    (2, '{"supplyUnits":[{"id":"two","label":"단위2","housingTypes":[]}],
                          "paths":[{"id":"path","label":"일반","kind":"GENERAL","checks":[]}],
                          "options":[]}')
                    """);
            sql.execute(new ClassPathResource(
                    "db/migration/V20261110_06__comparison_application_structure.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
            assertThat(count(sql, "comparison_supply_units")).isEqualTo(2);
            assertThat(count(sql, "comparison_paths")).isEqualTo(2);
            assertThat(count(sql, "comparison_options")).isOne();
            rejected(sql, "UPDATE comparison_options SET unit_key = 'two'", "23503");
            rejected(sql, "INSERT INTO comparison_options SELECT rule_set_id, 'duplicate',"
                    + " unit_key, path_key, recruitment_status FROM comparison_options", "23505");
            sql.execute("DELETE FROM announcements WHERE id = 1");
            assertThat(count(sql, "comparison_supply_units")).isOne();
            assertThat(count(sql, "comparison_options")).isZero();
        });
    }

    private void inSchema(SqlCheck check) throws Exception {
        String schema = "basic_comparison_migration_test";
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                sql.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
                sql.execute("CREATE TABLE announcements (id BIGINT PRIMARY KEY)");
                sql.execute("INSERT INTO users VALUES (1), (2)");
                sql.execute("INSERT INTO announcements VALUES (1), (2)");
                for (String table : List.of("announcement_rule_sets", "eligibility_profiles")) {
                    sql.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
                    sql.execute("INSERT INTO " + table + " VALUES (1)");
                }
                sql.execute(
                        new ClassPathResource(
                                        "db/migration/V20261110_04__basic_comparison_current_content.sql")
                                .getContentAsString(StandardCharsets.UTF_8));
                check.run(sql);
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static long count(Statement sql, String table) throws SQLException {
        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void rejected(Statement sql, String statement, String state) {
        assertThatThrownBy(() -> sql.execute(statement))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState")
                .isEqualTo(state);
    }

    @FunctionalInterface
    private interface SqlCheck {
        void run(Statement sql) throws Exception;
    }
}
