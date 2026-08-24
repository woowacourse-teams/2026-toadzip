package com.toadzip.backend.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties applicationDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource applicationDataSource(
            @Qualifier("applicationDataSourceProperties") DataSourceProperties dataSourceProperties
    ) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "shared.datasource", name = "enabled", havingValue = "true")
    @ConfigurationProperties("shared.datasource")
    public DataSourceProperties sharedDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConditionalOnProperty(prefix = "shared.datasource", name = "enabled", havingValue = "true")
    @ConfigurationProperties("shared.datasource.hikari")
    public HikariDataSource sharedDataSource(
            @Qualifier("sharedDataSourceProperties") DataSourceProperties dataSourceProperties
    ) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "shared.datasource", name = "enabled", havingValue = "true")
    public JdbcTemplate sharedJdbcTemplate(@Qualifier("sharedDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
