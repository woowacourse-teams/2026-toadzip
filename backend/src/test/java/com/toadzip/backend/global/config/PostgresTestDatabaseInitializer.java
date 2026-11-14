package com.toadzip.backend.global.config;

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

public class PostgresTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    /**
     * 운영 마이그레이션(V20261113_01)은 공고 공급표의 외래키를 모두 ON DELETE CASCADE로 둔다. 그래서 공급행을 지우면 그 칸과 칸이 든
     * 수량 묶음·임대조건 멤버도 함께 지워진다. 엔티티로 만든 테스트 스키마의 외래키에는 이 규칙이 없어 운영과 같게 바꾼다. 이미
     * CASCADE면 건드리지 않는다.
     */
    private static final String SUPPLY_SHEET_CASCADE = """
            DO $$
            DECLARE item record;
            BEGIN
                FOR item IN
                    SELECT c.conrelid::regclass AS child, c.conname, a.attname, c.confrelid::regclass AS parent
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confdeltype <> 'c'
                      AND c.conrelid::regclass::text IN ('announcement_supply_targets', 'supply_row_targets',
                          'supply_quotas', 'supply_quota_members', 'lease_terms', 'lease_term_members')
                LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', item.child, item.conname);
                    EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %s (id) ON DELETE CASCADE',
                            item.child, item.conname, item.attname, item.parent);
                END LOOP;
            END $$;
            """;

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
                cascadeSupplySheet(context);
            }
        });
    }

    private static void cascadeSupplySheet(ConfigurableApplicationContext context) {
        if (context.getBeanNamesForType(DataSource.class).length == 0) {
            return;
        }
        try (Connection connection = context.getBean(DataSource.class).getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(SUPPLY_SHEET_CASCADE);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("테스트 스키마의 공급표 외래키를 운영과 같게 두지 못했습니다.", exception);
        }
    }

    @Override
    public int getOrder() {
        // 개별 테스트의 스키마 초기화가 JDBC 연결을 열기 전에 접속 정보를 제공한다.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
