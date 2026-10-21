package com.toadzip.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class QueryLookupIndexMigrationTest {

    private static final String MIGRATION = "R__query_lookup_indexes.sql";
    private static final String TABLES = """
            CREATE TABLE housing_complexes (
                id bigint PRIMARY KEY, pnu text, supply_type text, admin_deleted boolean NOT NULL DEFAULT false);
            CREATE TABLE supply_rows (
                id bigint PRIMARY KEY, announcement_id bigint, housing_complex_id bigint, display_order integer);
            CREATE TABLE supply_targets (id bigint PRIMARY KEY, supply_row_id bigint, display_order integer);
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_행과_순서를_보존하면서_반복_조회가_인덱스를_사용한다() throws Exception {
        String schema = "lookup_index_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                statement.execute(TABLES);
                statement.execute("""
                        INSERT INTO housing_complexes
                        SELECT id, lpad(id::text, 19, '0'), 'HAPPY_HOUSING', false
                        FROM generate_series(1, 20000) id;
                        INSERT INTO supply_rows
                        SELECT id, id % 1000 + 1, id % 20000 + 1, id / 1000
                        FROM generate_series(1, 20000) id;
                        INSERT INTO supply_targets
                        SELECT id, id % 1000 + 1, id / 1000 FROM generate_series(1, 20000) id;
                        ANALYZE housing_complexes;
                        ANALYZE supply_rows;
                        ANALYZE supply_targets;
                        """);
                List<Lookup> lookups = List.of(
                        new Lookup("SELECT id FROM supply_rows WHERE announcement_id = 42 ORDER BY display_order, id",
                                "idx_supply_rows_announcement_order"),
                        new Lookup("SELECT id FROM supply_rows WHERE housing_complex_id = 42 AND announcement_id = 42",
                                "idx_supply_rows_complex_announcement"),
                        new Lookup("SELECT id FROM supply_targets WHERE supply_row_id = 42 ORDER BY display_order, id",
                                "idx_supply_targets_row_order"),
                        new Lookup("SELECT id FROM housing_complexes WHERE pnu = '0000000000000000042'"
                                + " AND supply_type = 'HAPPY_HOUSING' AND NOT admin_deleted",
                                "idx_housing_complexes_pnu_supply_type"));
                List<List<Long>> before = new ArrayList<>();
                for (Lookup lookup : lookups) {
                    before.add(rows(statement, lookup.sql()));
                    assertThat(plan(statement, lookup.sql())).contains("Seq Scan");
                }
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + MIGRATION));
                connection.commit();
                for (int index = 0; index < lookups.size(); index++) {
                    Lookup lookup = lookups.get(index);
                    assertThat(rows(statement, lookup.sql())).isEqualTo(before.get(index));
                    assertThat(plan(statement, lookup.sql())).contains(lookup.index());
                }
                assertThat(rows(statement, "SELECT count(*) FROM supply_rows")).containsExactly(20_000L);
                connection.commit();
            }
            finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
                connection.setSchema("public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void 핫픽스_인덱스_뒤에_개발_버전을_추가해도_순서_예외_없이_적용한다(@TempDir Path migrations)
            throws Exception {
        String schema = "lookup_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Files.writeString(migrations.resolve("V1__main_schema.sql"), TABLES);
        try (var resource = new ClassPathResource("db/migration/" + MIGRATION).getInputStream()) {
            Files.copy(resource, migrations.resolve(MIGRATION));
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + migrations).load();
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            Files.writeString(migrations.resolve("V2__develop_schema.sql"),
                    "ALTER TABLE supply_rows ADD COLUMN admin_excluded boolean NOT NULL DEFAULT false;");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThat(rows(statement, "SELECT count(*) FROM " + schema
                        + ".flyway_schema_history WHERE version IS NULL AND type = 'SQL' AND success"))
                        .containsExactly(1L);
            }
        }
        finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private List<Long> rows(Statement statement, String sql) throws Exception {
        List<Long> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getLong(1));
            }
        }
        return rows;
    }

    private String plan(Statement statement, String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (ResultSet result = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
            while (result.next()) {
                plan.append(result.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private record Lookup(String sql, String index) {
    }
}
