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
                    // 새 테이블이 단지를 참조하면 제품 참조(REFERENCE_TABLES)나 관리자 기록(ADMIN_RECORD_TABLES)에 넣는다.
                    // CASCADE 참조를 빠뜨리면 통합하며 지운 단지와 함께 그 행이 사라진다.
                    // 원천 연결·별칭과 저장 매칭 결정은 열이나 옮기는 규칙이 달라 Store가 따로 다룬다.
                    List<String> handled = new ArrayList<>(MyHomeComplexMergeStore.REFERENCE_TABLES);
                    handled.addAll(MyHomeComplexMergeStore.ADMIN_RECORD_TABLES);
                    handled.addAll(List.of("myhome_complex_links", "housing_complex_aliases",
                            "announcement_supply_match_decisions"));
                    assertThat(strings(query, """
                            SELECT DISTINCT conrelid::regclass::text FROM pg_constraint
                            WHERE contype = 'f' AND confrelid = 'public.housing_complexes'::regclass
                            """)).as("housing_complexes를 참조하는 테이블").containsExactlyInAnyOrderElementsOf(handled);
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
