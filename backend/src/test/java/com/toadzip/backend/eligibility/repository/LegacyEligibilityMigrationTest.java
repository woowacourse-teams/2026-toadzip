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

import javax.sql.DataSource;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class LegacyEligibilityMigrationTest {
    private static final String RULE_SET =
            """
            INSERT INTO announcement_rule_sets (id, announcement_id, status, recruitment_kind, applicant_kind,
                has_eligibility_relaxation, collected_at, eligibility_reference_date, structuring_status)
            VALUES (%d, %d, 'DRAFT', 'INITIAL', 'INDIVIDUAL', false, now(), '2026-10-15', 'PARTIAL')
            """;

    @Autowired private DataSource dataSource;

    @Test
    void 스키마와_고정_카탈로그를_만들고_원천_단지_테이블은_보존한다() throws Exception {
        inSchema(
                sql -> {
                    assertThat(count(sql, "eligibility_facts")).isEqualTo(45);
                    assertThat(count(sql, "input_gaps")).isEqualTo(22);
                    assertThat(count(sql, "special_status_types")).isEqualTo(4);
                    assertThat(count(sql, "announcement_complexes")).isEqualTo(1);
                    assertThat(count(sql, "eligibility_rule_set_complexes")).isZero();
                });
    }

    @Test
    void 같은_게시물에_초안을_둘_만들거나_스냅숏_없이_공개할_수_없다() throws Exception {
        inSchema(
                sql -> {
                    sql.execute(RULE_SET.formatted(1, 1));
                    rejected(sql, RULE_SET.formatted(2, 1), "23505");
                    rejected(
                            sql,
                            "UPDATE announcement_rule_sets SET status = 'PUBLISHED', published_at ="
                                + " now(), version = 1 WHERE id = 1",
                            "23514");
                    assertThat(count(sql, "announcement_rule_sets")).isEqualTo(1);
                });
    }

    @Test
    void 다른_판본의_원문_근거는_참조할_수_없고_판본_삭제는_소속_행만_지운다() throws Exception {
        inSchema(
                sql -> {
                    sql.execute(RULE_SET.formatted(1, 1));
                    sql.execute(RULE_SET.formatted(2, 2));
                    sql.execute(
                            """
                            INSERT INTO rule_set_documents (id, rule_set_id, file_name, media_type, byte_size, sha256,
                                page_count, evidence_level, fetched_at)
                            VALUES (1, 1, 'sample.pdf', 'application/pdf', 100, repeat('a', 64), 2,
                                'OFFICIAL_ATTACHMENT_UNVERIFIED', now())
                            """);
                    rejected(
                            sql,
                            """
                            INSERT INTO source_evidences (id, rule_set_id, document_id, pdf_page_from, pdf_page_to,
                                excerpt, verification_method) VALUES (1, 2, 1, 1, 1, '원문', 'TEXT')
                            """,
                            "23503");
                    sql.execute(
                            """
                            INSERT INTO source_evidences (id, rule_set_id, document_id, pdf_page_from, pdf_page_to,
                                excerpt, verification_method) VALUES (2, 1, 1, 1, 1, '원문', 'TEXT')
                            """);
                    sql.execute("DELETE FROM announcement_rule_sets WHERE id = 1");
                    assertThat(count(sql, "rule_set_documents")).isZero();
                    assertThat(count(sql, "source_evidences")).isZero();
                    assertThat(count(sql, "announcement_rule_sets")).isEqualTo(1);
                    assertThat(count(sql, "announcements")).isEqualTo(2);
                });
    }

    @Test
    void 프로필_상태와_값의_모순을_DB도_거부하고_회원_삭제시_프로필을_함께_지운다() throws Exception {
        inSchema(
                sql -> {
                    rejected(
                            sql,
                            "INSERT INTO eligibility_profiles (user_id, version, assets_krw) VALUES"
                                + " (1, 1, 0)",
                            "23514");
                    rejected(
                            sql,
                            "INSERT INTO eligibility_profiles (user_id, version, owns_car,"
                                + " owns_car_state) VALUES (1, 1, false, 'PROVIDED')",
                            "23514");
                    sql.execute(
                            "INSERT INTO eligibility_profiles (id, user_id, version) VALUES (1, 1,"
                                + " 1)");
                    sql.execute(
                            "INSERT INTO eligibility_profile_special_statuses (profile_id,"
                                + " status_code, state) VALUES (1, 'NEAR_POVERTY', 'UNKNOWN')");
                    sql.execute("DELETE FROM users WHERE id = 1");
                    assertThat(count(sql, "eligibility_profiles")).isZero();
                    assertThat(count(sql, "eligibility_profile_special_statuses")).isZero();
                });
    }

    private void inSchema(SqlCheck check) throws Exception {
        String schema = "eligibility_migration_test";
        try (Connection connection = dataSource.getConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                for (String table :
                        java.util.List.of(
                                "announcements",
                                "users",
                                "housing_complexes",
                                "housing_types",
                                "supply_rows",
                                "announcement_attachments",
                                "announcement_complexes")) {
                    sql.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
                }
                sql.execute("INSERT INTO announcements VALUES (1), (2)");
                sql.execute("INSERT INTO users VALUES (1)");
                sql.execute("INSERT INTO announcement_complexes VALUES (1)");
                for (String file :
                        java.util.List.of(
                                "V20261110_02__eligibility_current_rule_sets.sql",
                                "V20261110_03__eligibility_reference_data.sql")) {
                    sql.execute(
                            new ClassPathResource("db/migration/" + file)
                                    .getContentAsString(StandardCharsets.UTF_8));
                }
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
