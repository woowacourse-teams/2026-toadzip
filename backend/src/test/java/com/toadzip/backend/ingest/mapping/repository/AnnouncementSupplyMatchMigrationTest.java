package com.toadzip.backend.ingest.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AnnouncementSupplyMatchMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_보완_테이블을_제거하지만_제품_원천_실행_이력은_보존한다() throws Exception {
        verifyCleanup(true);
    }

    @Test
    void 보완_테이블이나_매칭_테이블이_없어도_안전하게_적용한다() throws Exception {
        verifyCleanup(false);
    }

    private void verifyCleanup(boolean legacy) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA supply_match_cleanup_test");
            statement.execute("SET search_path TO supply_match_cleanup_test");
            try {
                statement.execute("CREATE TABLE housing_complexes (id bigint PRIMARY KEY, name text NOT NULL)");
                statement.execute("CREATE TABLE housing_types (id bigint PRIMARY KEY, name text NOT NULL)");
                statement.execute("CREATE TABLE announcements (id bigint PRIMARY KEY, title text NOT NULL)");
                statement.execute("CREATE TABLE supply_rows (id bigint PRIMARY KEY, housing_type_id bigint "
                        + "REFERENCES housing_types(id))");
                statement.execute("CREATE TABLE myhome_announcement_source_rows (id bigint PRIMARY KEY)");
                statement.execute("CREATE TABLE data_pipeline_executions (id bigint PRIMARY KEY)");
                statement.execute("INSERT INTO housing_complexes VALUES (1, '기존 단지')");
                statement.execute("INSERT INTO housing_types VALUES (2, '기존 주택형')");
                statement.execute("INSERT INTO announcements VALUES (3, '기존 공고')");
                statement.execute("INSERT INTO supply_rows VALUES (4, 2)");
                statement.execute("INSERT INTO myhome_announcement_source_rows VALUES (5)");
                statement.execute("INSERT INTO data_pipeline_executions VALUES (6)");
                if (legacy) {
                    statement.execute(sql("V20261008_02__ingest_corrections.sql"));
                    statement.execute("INSERT INTO ingest_corrections VALUES "
                            + "('old', 0, '{}', 'admin', now(), null)");
                    statement.execute("CREATE TABLE announcement_supply_matches (id bigint PRIMARY KEY, "
                            + "housing_type_id bigint REFERENCES housing_types(id))");
                    statement.execute("INSERT INTO announcement_supply_matches VALUES (7, 2)");
                }
                statement.execute(sql("V20261009_02__remove_ingest_corrections.sql"));
                // 같은 정리 구문도 존재하지 않는 테이블에 실패하지 않는다.
                statement.execute(sql("V20261009_02__remove_ingest_corrections.sql"));
                try (var result = statement.executeQuery("""
                        SELECT c.name, t.name, a.title, s.housing_type_id, r.id, e.id
                        FROM housing_complexes c CROSS JOIN housing_types t CROSS JOIN announcements a
                        CROSS JOIN supply_rows s CROSS JOIN myhome_announcement_source_rows r
                        CROSS JOIN data_pipeline_executions e
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("기존 단지");
                    assertThat(result.getString(2)).isEqualTo("기존 주택형");
                    assertThat(result.getString(3)).isEqualTo("기존 공고");
                    assertThat(result.getLong(4)).isEqualTo(2);
                    assertThat(result.getLong(5)).isEqualTo(5);
                    assertThat(result.getLong(6)).isEqualTo(6);
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.tables WHERE table_schema = 'supply_match_cleanup_test'
                        AND table_name IN ('ingest_corrections', 'ingest_correction_changes',
                            'announcement_supply_matches')
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns WHERE table_schema = 'supply_match_cleanup_test'
                        AND table_name = 'housing_types' AND column_name = 'admin_correction'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA supply_match_cleanup_test CASCADE");
            }
        }
    }

    private String sql(String filename) throws Exception {
        return new ClassPathResource("db/migration/" + filename).getContentAsString(StandardCharsets.UTF_8);
    }
}
