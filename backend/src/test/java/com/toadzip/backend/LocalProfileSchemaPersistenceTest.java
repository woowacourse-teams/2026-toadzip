package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LocalProfileSchemaPersistenceTest {

    @Test
    void local_프로필은_종료_후에도_스키마를_유지한다() throws Exception {
        String databaseName = "local_profile_schema_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";

        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(BackendApplication.class)
                .environment(createIsolatedEnvironment(jdbcUrl))
                .run();

        applicationContext.close();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                ResultSet tables = connection.getMetaData().getTables(null, null, "NOTICES", new String[] {"TABLE"})) {
            assertTrue(tables.next());
        }
    }

    private ConfigurableEnvironment createIsolatedEnvironment(String jdbcUrl) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "isolatedTestProperties",
                Map.of(
                        "spring.datasource.url", jdbcUrl,
                        "spring.datasource.username", "sa",
                        "spring.datasource.password", "",
                        "spring.datasource.driver-class-name", "org.h2.Driver",
                        "spring.main.web-application-type", "none"
                )
        ));
        environment.setActiveProfiles("local");
        return environment;
    }
}
