package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LhAnnouncementLinkFailureMigrationTest {

    private static final String SCHEMA = "lh_link_failure_migration_test";
    private static final List<String> TABLES = List.of(
            "myhome_announcement_mapping_failures", "lh_announcement_enrichment_failures"
    );
    private static final String MIGRATION = "db/migration/V20260917_01__add_lh_link_failure_reasons.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_실패를_보존하며_새_사유를_허용하고_반복_적용해도_알수없는_사유는_거절한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            try {
                for (String table : TABLES) {
                    statement.execute("CREATE TABLE " + table
                            + " (reason varchar(50) NOT NULL CHECK (reason IN ('INVALID_VALUE')))");
                    statement.execute("INSERT INTO " + table + " VALUES ('INVALID_VALUE')");
                }
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
                assertLegacyFailure(statement, "myhome_announcement_mapping_failures");
                assertLegacyFailure(statement, "lh_announcement_enrichment_failures");
                for (MyHomeAnnouncementMappingFailureReason reason : MyHomeAnnouncementMappingFailureReason.values()) {
                    insertReason(connection, "myhome_announcement_mapping_failures", reason.name());
                }
                for (LhAnnouncementEnrichmentFailureReason reason : LhAnnouncementEnrichmentFailureReason.values()) {
                    insertReason(connection, "lh_announcement_enrichment_failures", reason.name());
                }
                for (String table : TABLES) {
                    assertThatThrownBy(() -> insertReason(connection, table, "UNKNOWN_REASON"))
                            .isInstanceOf(SQLException.class);
                }
            }
            finally {
                statement.execute("ROLLBACK");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    private void assertLegacyFailure(Statement statement, String table) throws SQLException {
        try (var result = statement.executeQuery("SELECT reason FROM " + table)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("INVALID_VALUE");
            assertThat(result.next()).isFalse();
        }
    }

    private void insertReason(Connection connection, String table, String reason) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + table + " VALUES (?)")) {
            statement.setString(1, reason);
            statement.executeUpdate();
        }
    }
}
