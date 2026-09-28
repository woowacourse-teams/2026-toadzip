package com.toadzip.backend.ingest.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MyHomeComplexLinkMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_ID를_그대로_연결하고_승인값과_외래키의_제약을_보장한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA myhome_complex_link_migration_test");
            try {
                connection.setSchema("myhome_complex_link_migration_test");
                sql.execute("CREATE TABLE housing_complexes (id BIGINT PRIMARY KEY, source_complex_identifier TEXT)");
                sql.execute("""
                        INSERT INTO housing_complexes VALUES
                            (3425, '31713155:HAPPY_HOUSING'), (3426, '31713154:HAPPY_HOUSING'),
                            (3427, '31713153:HAPPY_HOUSING'), (4000, 'ADMIN_ENTRY-HOUSING-COMPLEX-test')
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260927_01__myhome_complex_links_and_verified_merges.sql"));
                try (var rows = sql.executeQuery("SELECT count(*) FROM myhome_complex_links")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(3);
                }
                try (var rows = sql.executeQuery("SELECT housing_complex_id FROM myhome_complex_links ORDER BY 1")) {
                    for (long id : java.util.List.of(3425L, 3426L, 3427L)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(id);
                    }
                }
                assertThatThrownBy(() -> sql.execute("""
                        INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id)
                        VALUES ('31713155:HAPPY_HOUSING', 3426)
                        """)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("""
                        INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id)
                        VALUES ('999:HAPPY_HOUSING', 999)
                        """)).isInstanceOf(SQLException.class);
                sql.execute("""
                        INSERT INTO myhome_complex_merges
                            (id, representative_id, snapshot_version, adopted_household_count, complex_ids,
                             before_state, evidence, preview_hash, reason, verified_by, merged_at)
                        VALUES ('00000000-0000-0000-0000-000000000231', 3425, 1, 11, '[3425,3426,3427]',
                            '{}', '{}', repeat('a', 64), '통합 테스트 근거', 'admin', now())
                        """);
                assertThatThrownBy(() -> sql.execute("""
                        UPDATE myhome_complex_links SET merge_id = '00000000-0000-0000-0000-000000000231'
                        WHERE housing_complex_id = 3426
                        """)).isInstanceOf(SQLException.class);
                sql.execute("""
                        UPDATE myhome_complex_links SET housing_complex_id = 3425,
                            merge_id = '00000000-0000-0000-0000-000000000231', approved_household_count = 1
                        WHERE housing_complex_id = 3426
                        """);
                sql.execute("""
                        INSERT INTO housing_complex_aliases VALUES
                            (3426, 3425, '00000000-0000-0000-0000-000000000231')
                        """);
                sql.execute("DELETE FROM housing_complexes WHERE id = 3426");
                try (var rows = sql.executeQuery("""
                        SELECT housing_complex_id, approved_household_count
                        FROM myhome_complex_links WHERE source_complex_identifier = '31713154:HAPPY_HOUSING'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(3425);
                    assertThat(rows.getInt(2)).isOne();
                }
            }
            finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA myhome_complex_link_migration_test CASCADE");
            }
        }
    }
}
