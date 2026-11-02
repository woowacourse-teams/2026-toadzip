package com.toadzip.backend.ingest.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 단지 통합은 단지 행을 지웠다가 되돌릴 때 열과 참조 테이블을 손으로 적은 목록으로 다룬다.
 * 마이그레이션이 열이나 단지를 참조하는 테이블을 더하면 목록도 함께 고치도록, 모든 마이그레이션을 적용한 스키마와 대조한다.
 * 테스트 프로필의 공유 스키마는 엔티티로 만들어 실제 마이그레이션과 다르므로 새 데이터베이스를 쓴다.
 */
class MyHomeComplexMergeStoreSchemaTest {

    @Test
    void 통합_저장소가_다루는_단지_열과_참조_테이블은_마이그레이션_스키마와_같다() throws Exception {
        String database = "merge_store_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                Flyway.configure().dataSource(PostgresTestDatabases.primaryUrl(database), "toadzip_test", "toadzip_test")
                        .locations("classpath:db/migration").load().migrate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    assertThat(MyHomeComplexMergeStore.COMPLEX_COLUMNS)
                            .as("housing_complexes 열이 바뀌면 MyHomeComplexMergeStore.COMPLEX_COLUMNS도 고친다")
                            .containsExactlyInAnyOrderElementsOf(strings(query, """
                                    SELECT column_name FROM information_schema.columns
                                    WHERE table_schema = 'public' AND table_name = 'housing_complexes'
                                    """));
                    // 새 테이블이 단지를 참조하면 MyHomeComplexMergeStore의 잠금·스냅숏·이전·복구 목록에 넣고 여기에 더한다.
                    // CASCADE 참조를 빠뜨리면 통합하며 지운 단지와 함께 그 행이 사라진다.
                    assertThat(strings(query, """
                            SELECT DISTINCT conrelid::regclass::text FROM pg_constraint
                            WHERE contype = 'f' AND confrelid = 'public.housing_complexes'::regclass
                            """)).as("housing_complexes를 참조하는 테이블").containsExactlyInAnyOrder(
                            "housing_types", "supply_rows", "favorite_housing_complexes",
                            "announcement_application_schedules", "announcement_complexes", "myhome_complex_links",
                            "housing_complex_aliases", "housing_complex_reviews", "complex_comparison_links",
                            "announcement_supply_match_decisions", "lh_household_complex_links",
                            "lh_household_target_decisions");
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private static List<String> strings(Statement query, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var rows = query.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(PostgresTestDatabases.primaryUrl(database), "toadzip_test", "toadzip_test");
    }
}
