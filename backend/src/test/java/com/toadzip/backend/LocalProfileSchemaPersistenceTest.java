package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LocalProfileSchemaPersistenceTest {

    @Test
    void local_프로필은_종료_후에도_스키마를_유지한다() throws Exception {
        String jdbcUrl = testDatabaseUrl();

        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(BackendApplication.class)
                .environment(createIsolatedEnvironment(jdbcUrl))
                .run();

        applicationContext.close();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                ResultSet tables = connection.getMetaData().getTables(null, null, "notices", new String[] {"TABLE"})) {
            assertAll(
                    () -> assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName()),
                    () -> assertTrue(tables.next())
            );
        }
    }

    private String testDatabaseUrl() {
        String testPort = System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432");
        return "jdbc:postgresql://127.0.0.1:" + testPort + "/toadzip_test";
    }

    private ConfigurableEnvironment createIsolatedEnvironment(String jdbcUrl) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "isolatedTestProperties",
                Map.of(
                        "spring.datasource.url", jdbcUrl,
                        "spring.datasource.username", "toadzip_test",
                        "spring.datasource.password", "toadzip_test",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver",
                        "spring.main.web-application-type", "none"
                )
        ));
        environment.setActiveProfiles("local");
        return environment;
    }
}
