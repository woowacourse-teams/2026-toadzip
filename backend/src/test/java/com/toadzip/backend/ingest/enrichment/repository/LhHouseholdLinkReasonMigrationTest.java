package com.toadzip.backend.ingest.enrichment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class LhHouseholdLinkReasonMigrationTest {

    private static final String SCHEMA = "lh_household_link_reason_migration_test";
    private static final String TABLE = "lh_household_enrichment_failures";

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_실패를_보존하며_저장_연결과_단지_결정_재확인_사유를_허용하고_알수없는_사유는_거절한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            try {
                statement.execute("CREATE TABLE " + TABLE + " (reason varchar(40) NOT NULL CONSTRAINT "
                        + TABLE + "_reason_check CHECK (reason IN ('INVALID_SOURCE')))");
                statement.execute("INSERT INTO " + TABLE + " VALUES ('INVALID_SOURCE')");
                for (String migration : List.of("V20261024_04__lh_household_link_review_reason.sql",
                        "V20261024_06__lh_household_target_decision_reason.sql")) {
                    var resource = new ClassPathResource("db/migration/" + migration);
                    ScriptUtils.executeSqlScript(connection, resource);
                    ScriptUtils.executeSqlScript(connection, resource);
                }

                for (LhHouseholdEnrichmentFailureReason reason : LhHouseholdEnrichmentFailureReason.values()) {
                    insertReason(connection, reason.name());
                }
                assertThatThrownBy(() -> insertReason(connection, "UNKNOWN_REASON")).isInstanceOf(SQLException.class);
                try (var result = statement.executeQuery("SELECT count(*) FROM " + TABLE)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(LhHouseholdEnrichmentFailureReason.values().length + 1);
                }
            }
            finally {
                statement.execute("ROLLBACK");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    private void insertReason(Connection connection, String reason) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + TABLE + " VALUES (?)")) {
            statement.setString(1, reason);
            statement.executeUpdate();
        }
    }
}
