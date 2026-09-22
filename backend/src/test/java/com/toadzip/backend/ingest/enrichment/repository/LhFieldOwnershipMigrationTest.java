package com.toadzip.backend.ingest.enrichment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.MigrationSqlSection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LhFieldOwnershipMigrationTest {

    private static final String SCHEMA = "lh_field_ownership_migration_test";
    private static final String OWNERSHIP_MIGRATION =
            "V20260918_01__add_lh_field_ownership.sql";
    private static final String CORRECTION_MIGRATION =
            "V20260918_02__correct_lh_household_count_ownership.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_LH_실제_보강값만_소유값으로_backfill한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareLegacySchema(connection);
            try {
                ScriptUtils.executeSqlScript(connection, MigrationSqlSection.resource(OWNERSHIP_MIGRATION));
                ScriptUtils.executeSqlScript(connection, MigrationSqlSection.resource(CORRECTION_MIGRATION));

                assertOwnershipValues(connection);
                assertOwnershipConstraints(connection);
            }
            finally {
                dropTestSchema(connection);
            }
        }
    }

    private void prepareLegacySchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            statement.execute("""
                    CREATE TABLE announcements (
                        id BIGSERIAL PRIMARY KEY,
                        lh_pan_id VARCHAR(255),
                        reception_method VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    INSERT INTO announcements (lh_pan_id, reception_method)
                    VALUES ('100', 'VISIT'), ('200', 'ONLINE'), (NULL, 'VISIT')
                    """);
            statement.execute("""
                    CREATE TABLE supply_rows (
                        id BIGSERIAL PRIMARY KEY,
                        lh_source_supply_row_identifier VARCHAR(255),
                        total_supply_household_count INTEGER
                    )
                    """);
            statement.execute("""
                    INSERT INTO supply_rows (lh_source_supply_row_identifier, total_supply_household_count)
                    VALUES
                        ('LH:100:SUPPLY:0', 100),
                        ('LH:100:SUPPLY:1', 20),
                        ('LH:100:SUPPLY:2', 10),
                        ('LH:100:SUPPLY:0', 20),
                        ('LH:100:SUPPLY:0', 50),
                        ('LH:999:SUPPLY:0', 30),
                        (NULL, 20)
                    """);
            statement.execute("""
                    CREATE TABLE lh_announcement_supply_source (
                        id BIGSERIAL PRIMARY KEY,
                        pan_id VARCHAR(255),
                        source_order INTEGER,
                        total_unit_count VARCHAR(255),
                        supplied_unit_count VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    INSERT INTO lh_announcement_supply_source (
                        pan_id, source_order, total_unit_count, supplied_unit_count
                    )
                    VALUES
                        ('100', 0, '100', '20'),
                        ('100', 1, NULL, NULL),
                        ('100', 2, NULL, '10')
                    """);
        }
    }

    private void assertOwnershipValues(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet announcements = statement.executeQuery("""
                        SELECT lh_reception_place_owned
                        FROM announcements
                        ORDER BY id
                        """)) {
            assertThat(announcements.next()).isTrue();
            assertThat(announcements.getBoolean(1)).isTrue();
            assertThat(announcements.next()).isTrue();
            assertThat(announcements.getBoolean(1)).isFalse();
            assertThat(announcements.next()).isTrue();
            assertThat(announcements.getBoolean(1)).isFalse();
        }
        try (Statement statement = connection.createStatement();
                ResultSet supplyRows = statement.executeQuery("""
                        SELECT lh_total_supply_household_count_owned,
                               lh_total_supply_household_count_enriched
                        FROM supply_rows
                        ORDER BY id
                        """)) {
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isTrue();
            assertThat(supplyRows.getBoolean(2)).isTrue();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isFalse();
            assertThat(supplyRows.getBoolean(2)).isFalse();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isTrue();
            assertThat(supplyRows.getBoolean(2)).isFalse();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isTrue();
            assertThat(supplyRows.getBoolean(2)).isFalse();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isFalse();
            assertThat(supplyRows.getBoolean(2)).isFalse();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isTrue();
            assertThat(supplyRows.getBoolean(2)).isTrue();
            assertThat(supplyRows.next()).isTrue();
            assertThat(supplyRows.getBoolean(1)).isFalse();
            assertThat(supplyRows.getBoolean(2)).isFalse();
        }
    }

    private void assertOwnershipConstraints(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT table_name, column_name, is_nullable, column_default
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND column_name IN (
                              'lh_reception_place_owned',
                              'lh_total_supply_household_count_owned',
                              'lh_total_supply_household_count_enriched'
                          )
                        ORDER BY table_name, ordinal_position
                        """)) {
            assertOwnershipConstraint(result, "announcements", "lh_reception_place_owned");
            assertOwnershipConstraint(result, "supply_rows", "lh_total_supply_household_count_owned");
            assertOwnershipConstraint(result, "supply_rows", "lh_total_supply_household_count_enriched");
        }
    }

    private void assertOwnershipConstraint(ResultSet result, String tableName, String columnName) throws Exception {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("table_name")).isEqualTo(tableName);
        assertThat(result.getString("column_name")).isEqualTo(columnName);
        assertThat(result.getString("is_nullable")).isEqualTo("NO");
        assertThat(result.getString("column_default")).isEqualTo("false");
    }

    private void dropTestSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
