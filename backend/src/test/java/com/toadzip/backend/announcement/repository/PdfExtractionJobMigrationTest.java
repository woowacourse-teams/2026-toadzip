package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class PdfExtractionJobMigrationTest {
    private static final String SCHEMA = "ai_jobs_migration_test";
    private static final String WORKER = "toadzip_ai_worker";
    private static final String JOB = """
            INSERT INTO ai_jobs (kind, announcement_id, request_source, status, max_attempts, next_attempt_at,
                file_name, pdf_sha256, pdf, created_at)
            VALUES ('ANNOUNCEMENT_PDF_EXTRACTION', 1, 'ADMIN', '%s', 3, now(), 'a.pdf', 'ab', '\\x25', now())
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    void 예전_추출_결과를_성공_작업으로_옮긴다() throws Exception {
        inMigratedSchema(sql -> {
            try (var rows = sql.executeQuery("""
                    SELECT announcement_id, status, file_name, pdf_sha256, result ->> 'totalPages', completed_at,
                        input IS NULL, pdf IS NULL FROM ai_jobs
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1L);
                assertThat(rows.getString(2)).isEqualTo("SUCCEEDED");
                assertThat(rows.getString(3)).isEqualTo("old.pdf");
                assertThat(rows.getString(4)).isEqualTo("cd".repeat(32));
                assertThat(rows.getString(5)).isEqualTo("9");
                assertThat(rows.getTimestamp(6).toInstant()).hasToString("2026-10-26T01:00:00Z");
                assertThat(rows.getBoolean(7)).isTrue();
                assertThat(rows.getBoolean(8)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        });
    }

    @Test
    void 재시도를_기다리는_작업이_있으면_같은_공고에_작업을_더_넣지_못한다() throws Exception {
        inMigratedSchema(sql -> {
            sql.execute(JOB.formatted("RETRY").replace("now(), 'a.pdf'", "now() + interval '1 minute', 'a.pdf'"));

            assertRejected(sql, JOB.formatted("PENDING"), "23505");
        });
    }

    @Test
    void 끝난_작업에는_PDF를_남길_수_없다() throws Exception {
        inMigratedSchema(sql -> {
            sql.execute(JOB.formatted("PENDING"));

            assertRejected(sql, """
                    UPDATE ai_jobs SET status = 'SUCCEEDED', result = '{}', completed_at = now()
                    WHERE status = 'PENDING'
                    """, "23514");
            assertRejected(sql, """
                    UPDATE ai_jobs SET status = 'FAILED', error_code = 'PDF_INVALID', completed_at = now()
                    WHERE status = 'PENDING'
                    """, "23514");
        });
    }

    @Test
    void 워커_역할은_작업_상태와_결과만_고칠_수_있다() throws Exception {
        inMigratedSchema(sql -> {
            try (var rows = sql.executeQuery("""
                    SELECT has_table_privilege('%1$s', 'ai_jobs', 'SELECT'),
                        has_column_privilege('%1$s', 'ai_jobs', 'pdf', 'UPDATE'),
                        has_column_privilege('%1$s', 'ai_jobs', 'result', 'UPDATE'),
                        has_column_privilege('%1$s', 'ai_jobs', 'input', 'UPDATE'),
                        has_column_privilege('%1$s', 'ai_jobs', 'announcement_id', 'UPDATE'),
                        has_table_privilege('%1$s', 'ai_jobs', 'INSERT'),
                        has_table_privilege('%1$s', 'ai_jobs', 'DELETE')
                    """.formatted(WORKER))) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getBoolean(4)).isFalse();
                assertThat(rows.getBoolean(5)).isFalse();
                assertThat(rows.getBoolean(6)).isFalse();
                assertThat(rows.getBoolean(7)).isFalse();
            }
        });
    }

    private void inMigratedSchema(SqlCheck check) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + WORKER + "') "
                    + "THEN CREATE ROLE " + WORKER + "; END IF; END $$");
            sql.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                sql.execute("CREATE TABLE announcements (id BIGINT PRIMARY KEY)");
                sql.execute("""
                        CREATE TABLE announcement_pdf_extractions (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, announcement_id BIGINT NOT NULL,
                            pdf_sha256 VARCHAR(64) NOT NULL, file_name VARCHAR(500) NOT NULL, result TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL)
                        """);
                sql.execute("INSERT INTO announcements VALUES (1)");
                sql.execute("""
                        INSERT INTO announcement_pdf_extractions (announcement_id, pdf_sha256, file_name, result, created_at)
                        VALUES (1, '%s', 'old.pdf', '{"json":"{}","pages":[1],"totalPages":9,"warnings":[],
                            "usage":{"promptTokens":1,"outputTokens":2,"thinkingTokens":null}}',
                            '2026-10-26T01:00:00Z')
                        """.formatted("cd".repeat(32)));
                // DO $$ 블록이 있어 문장을 나누지 않고 한 번에 보낸다.
                sql.execute(new ClassPathResource("db/migration/V20261103_01__ai_jobs.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
                check.run(sql);
            }
            finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    private static void assertRejected(Statement sql, String statement, String state) {
        assertThatThrownBy(() -> sql.execute(statement))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo(state);
    }

    @FunctionalInterface
    private interface SqlCheck {
        void run(Statement sql) throws Exception;
    }
}
