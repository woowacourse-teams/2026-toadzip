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
class CorrectionDraftMigrationTest {
    private static final String SCHEMA = "correction_drafts_migration_test";
    private static final String DRAFT = """
            INSERT INTO announcement_correction_drafts (announcement_id, admin_login_identifier, payload,
                base_snapshot_token, revision, created_at, updated_at)
            VALUES (1, '%s', '%s', 'token', 1, now(), now())
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    void 공고를_지우면_그_공고의_초안도_지운다() throws Exception {
        inMigratedSchema(sql -> {
            sql.execute(DRAFT.formatted("admin", "{}"));

            sql.execute("DELETE FROM announcements WHERE id = 1");

            try (var rows = sql.executeQuery("SELECT count(*) FROM announcement_correction_drafts")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        });
    }

    @Test
    void 한_관리자는_한_공고에_초안을_하나만_가진다() throws Exception {
        inMigratedSchema(sql -> {
            sql.execute(DRAFT.formatted("admin", "{}"));
            sql.execute(DRAFT.formatted("other", "{}"));

            assertRejected(sql, DRAFT.formatted("admin", "{}"), "23505");
        });
    }

    @Test
    void 작업_상태는_JSON_객체만_저장한다() throws Exception {
        inMigratedSchema(sql -> assertRejected(sql, DRAFT.formatted("admin", "[]"), "23514"));
    }

    private void inMigratedSchema(SqlCheck check) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                sql.execute("CREATE TABLE announcements (id BIGINT PRIMARY KEY)");
                sql.execute("INSERT INTO announcements VALUES (1)");
                sql.execute(new ClassPathResource("db/migration/V20261103_03__announcement_correction_drafts.sql")
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
