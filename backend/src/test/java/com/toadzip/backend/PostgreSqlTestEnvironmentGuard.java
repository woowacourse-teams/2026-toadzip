package com.toadzip.backend;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

class PostgreSqlTestEnvironmentGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String TEST_DATABASE_URL_PATTERN =
            "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/toadzip_test";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();

        validateUrl(environment.getProperty("spring.datasource.url"));
        validateValue(
                "spring.datasource.username",
                environment.getProperty("spring.datasource.username"),
                "toadzip_test"
        );
        validateValue(
                "spring.datasource.driver-class-name",
                environment.getProperty("spring.datasource.driver-class-name"),
                "org.postgresql.Driver"
        );
        validateValue(
                "spring.jpa.hibernate.ddl-auto",
                environment.getProperty("spring.jpa.hibernate.ddl-auto"),
                "create-drop"
        );
    }

    private void validateUrl(String jdbcUrl) {
        if (jdbcUrl != null && jdbcUrl.matches(TEST_DATABASE_URL_PATTERN)) {
            return;
        }

        throw new IllegalStateException(
                "spring.datasource.url must be jdbc:postgresql://127.0.0.1:<numeric-port>/toadzip_test"
        );
    }

    private void validateValue(String propertyName, String actualValue, String expectedValue) {
        if (expectedValue.equals(actualValue)) {
            return;
        }

        throw new IllegalStateException(propertyName + " must be " + expectedValue);
    }
}
