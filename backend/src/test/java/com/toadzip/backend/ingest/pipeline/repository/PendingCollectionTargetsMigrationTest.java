package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
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
class PendingCollectionTargetsMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기관별_요청을_구분하고_같은_미완료_요청의_중복_저장을_막는다() throws Exception {
        try (Connection connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA pending_targets_migration_test");
            sql.execute("SET search_path TO pending_targets_migration_test");
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_01__pending_collection_targets.sql"));
                sql.execute("INSERT INTO pending_collection_targets(source,target_key,payload,list_page) "
                        + "VALUES ('SH_ANNOUNCEMENT','100','{}',1), ('GH_ANNOUNCEMENT','100','{}',1)");
                assertThatThrownBy(() -> sql.execute("INSERT INTO pending_collection_targets(source,target_key,payload,list_page) "
                        + "VALUES ('SH_ANNOUNCEMENT','100','{}',2)")).isInstanceOf(SQLException.class);
                try (var rows = sql.executeQuery("SELECT count(*) FROM pending_collection_targets")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
            }
            finally {
                sql.execute("RESET search_path");
                sql.execute("DROP SCHEMA pending_targets_migration_test CASCADE");
            }
        }
    }
}
