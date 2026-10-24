package com.toadzip.backend.global.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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

    /** 운영과 같은 Flyway 마이그레이션을 적용한 새 데이터베이스를 만들고 URL을 반환한다. */
    public static String migratedPrimaryUrl(String databasePrefix) {
        String databaseName = databasePrefix + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                primaryUrl(PRIMARY.getDatabaseName()), PRIMARY.getUsername(), PRIMARY.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다.", exception);
        }
        String url = primaryUrl(databaseName);
        Flyway.configure().dataSource(url, PRIMARY.getUsername(), PRIMARY.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return url;
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
