package com.toadzip.backend.privacy.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", matchIfMissing = true)
public class PrivacyMigrationConfiguration {

    @Bean
    public FlywayMigrationStrategy privacyMigrationStrategy() {
        return this::migrate;
    }

    public void migrate(Flyway applicationFlyway) {
        DataSource dataSource = applicationFlyway.getConfiguration().getDataSource();
        verifyCompatibleSchema(dataSource);
        applicationFlyway.migrate();
        privacyFlyway(dataSource).migrate();
    }

    public Flyway privacyFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource)
                .defaultSchema("public")
                .table("privacy_flyway_schema_history")
                .locations("classpath:db/privacy")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
    }

    private void verifyCompatibleSchema(DataSource dataSource) {
        ClassPathResource resource = new ClassPathResource("privacy/schema-preflight.sql");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(resource.getContentAsString(StandardCharsets.UTF_8));
            } finally {
                connection.rollback();
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Privacy schema preflight failed; database recovery requires "
                    + "an explicitly approved plan. No application migration was started.", exception);
        }
    }
}
