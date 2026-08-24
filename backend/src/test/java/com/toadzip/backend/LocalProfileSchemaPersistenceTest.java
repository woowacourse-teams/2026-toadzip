package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class LocalProfileSchemaPersistenceTest {

    @Test
    void local_프로필은_종료_후에도_스키마를_유지한다() throws Exception {
        String databaseName = "local_profile_schema_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";

        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("local")
                .properties(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run();

        applicationContext.close();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                ResultSet tables = connection.getMetaData().getTables(null, null, "NOTICES", new String[] {"TABLE"})) {
            assertTrue(tables.next());
        }
    }
}
