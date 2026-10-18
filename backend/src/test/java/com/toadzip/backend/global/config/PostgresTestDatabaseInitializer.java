package com.toadzip.backend.global.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

public class PostgresTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!context.getEnvironment().acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testcontainers", PostgresTestDatabases.connectionProperties())
        );
    }

    @Override
    public int getOrder() {
        // 개별 테스트의 스키마 초기화가 JDBC 연결을 열기 전에 접속 정보를 제공한다.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
