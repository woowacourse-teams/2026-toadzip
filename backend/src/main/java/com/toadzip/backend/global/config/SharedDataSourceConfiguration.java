package com.toadzip.backend.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SharedDataSourceConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("shared")
    @ConfigurationProperties("app.datasource.shared")
    public DataSourceProperties sharedDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(defaultCandidate = false)
    @Qualifier("shared")
    @ConfigurationProperties("app.datasource.shared.configuration")
    public HikariDataSource sharedDataSource(
            @Qualifier("shared") DataSourceProperties sharedDataSourceProperties
    ) {
        return sharedDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
