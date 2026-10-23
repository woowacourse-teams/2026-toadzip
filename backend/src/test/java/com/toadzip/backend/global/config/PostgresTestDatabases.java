package com.toadzip.backend.global.config;

import java.util.Map;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestDatabases {

    private static final PostgreSQLContainer PRIMARY = database("toadzip_test");
    private static final PostgreSQLContainer SHARED = database("toadzip_shared_test");

    static {
        // Spring 컨텍스트가 닫혀도 유지하고, JVM 종료 후 Testcontainers의 Ryuk이 정리한다.
        Startables.deepStart(PRIMARY, SHARED).join();
    }

    private PostgresTestDatabases() {
    }

    public static String primaryUrl(String databaseName) {
        return jdbcUrl(PRIMARY, databaseName);
    }

    public static String sharedUrl() {
        return jdbcUrl(SHARED, SHARED.getDatabaseName());
    }

    public static Map<String, Object> connectionProperties() {
        return Map.of(
                "spring.datasource.url", primaryUrl(PRIMARY.getDatabaseName()),
                "spring.datasource.username", PRIMARY.getUsername(),
                "spring.datasource.password", PRIMARY.getPassword(),
                "app.datasource.shared.url", sharedUrl(),
                "app.datasource.shared.username", SHARED.getUsername(),
                "app.datasource.shared.password", SHARED.getPassword()
        );
    }

    private static PostgreSQLContainer database(String databaseName) {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName(databaseName)
                .withUsername(databaseName)
                .withPassword(databaseName)
                // Small test fixtures spend more time compiling queries than executing them with JIT.
                .withCommand("postgres", "-c", "max_connections=300", "-c", "jit=off")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));
    }

    private static String jdbcUrl(PostgreSQLContainer container, String databaseName) {
        // 스키마별 테스트가 currentSchema를 붙일 수 있도록 쿼리 매개변수 없는 URL을 제공한다.
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + databaseName;
    }
}
