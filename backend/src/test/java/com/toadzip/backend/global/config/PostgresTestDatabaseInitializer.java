package com.toadzip.backend.global.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;

public class PostgresTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    /** 엔티티로 만든 테스트 스키마에도 운영과 같은 공급표 동기화 함수·트리거를 둔다. Flyway를 쓰는 테스트에는 이미 있고 다시 실행해도 된다. */
    private static final String SUPPLY_SHEET_SYNC = "db/migration/V20261113_02__supply_sheet_sync.sql";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!context.getEnvironment().acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testcontainers", PostgresTestDatabases.connectionProperties())
        );
        context.addApplicationListener(event -> {
            if (event instanceof ContextRefreshedEvent refreshed && refreshed.getApplicationContext() == context) {
                installSupplySheetSync(context);
            }
        });
    }

    private static void installSupplySheetSync(ConfigurableApplicationContext context) {
        if (context.getBeanNamesForType(DataSource.class).length == 0) {
            return;
        }
        try (Connection connection = context.getBean(DataSource.class).getConnection();
                Statement statement = connection.createStatement()) {
            if (!statement.executeQuery("SELECT to_regclass('supply_row_targets') IS NOT NULL").next()
                    || !statement.getResultSet().getBoolean(1)) {
                return;
            }
            // 함수 본문의 ';'로 나누지 않도록 파일을 한 번에 보낸다. PostgreSQL JDBC가 달러 인용을 지켜 문장을 나눈다.
            statement.execute(new ClassPathResource(SUPPLY_SHEET_SYNC).getContentAsString(StandardCharsets.UTF_8));
        }
        catch (SQLException | IOException exception) {
            throw new IllegalStateException("테스트 스키마에 공급표 동기화를 두지 못했습니다.", exception);
        }
    }

    @Override
    public int getOrder() {
        // 개별 테스트의 스키마 초기화가 JDBC 연결을 열기 전에 접속 정보를 제공한다.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
