package com.toadzip.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DataSourceTimeoutConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(SharedDataSourceConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                    "app.datasource.shared.url=jdbc:postgresql://127.0.0.1:1/unused");

    @Test
    void 두_연결_풀에_요청_대기와_JDBC_통신_제한을_각각_적용한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            for (HikariDataSource dataSource : context.getBeansOfType(HikariDataSource.class).values()) {
                assertThat(dataSource.getConnectionTimeout()).isEqualTo(5_000);
                assertThat(dataSource.getValidationTimeout()).isEqualTo(2_000);
                assertThat(dataSource.getDataSourceProperties())
                        .containsEntry("connectTimeout", "5")
                        .containsEntry("loginTimeout", "10")
                        .containsEntry("socketTimeout", "120")
                        .containsEntry("tcpKeepAlive", "true");
            }
            assertThat(context.getBeansOfType(HikariDataSource.class)).hasSize(2);
        });
    }

    @Test
    void 긴_수집_SQL의_읽기_제한을_두_풀에서_함께_조절할_수_있다() {
        contextRunner.withPropertyValues("DB_SOCKET_TIMEOUT_SECONDS=300").run(context -> {
            assertThat(context).hasNotFailed();
            for (HikariDataSource dataSource : context.getBeansOfType(HikariDataSource.class).values()) {
                assertThat(dataSource.getDataSourceProperties()).containsEntry("socketTimeout", "300");
            }
        });
    }
}
