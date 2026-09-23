package com.toadzip.backend.ingest.collection.repository;

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
class LhAnnouncementCollectionLinkMigrationTest {

    private static final String SCHEMA = "lh_announcement_collection_link_migration_test";

    private static final String MIGRATION =
            "V20260911_01__create_lh_announcement_collection_links.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 공고별_LH_연결_테이블과_인덱스를_생성한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareSchema(connection);
            try {
                ScriptUtils.executeSqlScript(connection, MigrationSqlSection.resource(MIGRATION));

                assertTable(connection);
                assertThat(uniqueConstraintExists(connection)).isTrue();
                assertThat(indexExists(connection)).isTrue();
            }
            finally {
                dropSchema(connection);
            }
        }
    }

    private void prepareSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
        }
    }

    private void assertTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = current_schema()
                          AND table_name = 'lh_announcement_collection_links'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isOne();
        }
    }

    private boolean uniqueConstraintExists(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT 1
                        FROM pg_constraint
                        WHERE connamespace = current_schema()::regnamespace
                          AND conname = 'uk_lh_announcement_link_source_announcement'
                        """)) {
            return result.next();
        }
    }

    private boolean indexExists(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT 1
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND indexname = 'idx_lh_announcement_link_source_request_hash'
                        """)) {
            return result.next();
        }
    }

    private void dropSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
