package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
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
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupplySourceOwnershipMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_LH_확장행의_원천을_고정해_표시순서가_바뀌어도_대응을_유지한다() throws Exception {
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA supply_ownership_upgrade");
            sql.execute("SET search_path TO supply_ownership_upgrade");
            try {
                sql.execute("CREATE TABLE announcements(id bigint, source_announcement_identifier text)");
                sql.execute("CREATE TABLE supply_row_groups(id bigint, source_row_identifier text)");
                sql.execute("CREATE TABLE supply_rows(id bigint, announcement_id bigint, display_order int, "
                        + "source_supply_row_identifier text, supply_group_id bigint)");
                sql.execute("INSERT INTO announcements VALUES (1, 'a_%')");
                sql.execute("INSERT INTO supply_row_groups VALUES (9, 'source-group')");
                sql.execute("""
                        INSERT INTO supply_rows VALUES
                        (1, 1, 0, 'source-1', NULL), (2, 1, 1, 'a_%:LH:expanded-1', NULL),
                        (3, 1, 2, 'source-2', NULL), (4, 1, 3, 'a_%:LH:expanded-2', NULL),
                        (5, 1, 4, 'MANUAL:operator-row', NULL), (6, 1, 5, 'ADMIN:group-member', 9)
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261019_08__supply_source_ownership.sql"));
                sql.execute("UPDATE supply_rows SET display_order = 100 - display_order");
                var owners = new LinkedHashMap<Long, String>();
                try (var result = sql.executeQuery("SELECT id, myhome_source_row_identifier FROM supply_rows ORDER BY id")) {
                    while (result.next()) { owners.put(result.getLong(1), result.getString(2)); }
                }
                assertThat(owners).containsEntry(1L, "source-1").containsEntry(2L, "source-1")
                        .containsEntry(3L, "source-2").containsEntry(4L, "source-2")
                        .containsEntry(5L, null).containsEntry(6L, "source-group");
            } finally {
                sql.execute("RESET search_path");
                sql.execute("DROP SCHEMA supply_ownership_upgrade CASCADE");
            }
        }
    }
}
