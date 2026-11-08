package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.housing.domain.RentalType;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 테스트 프로필은 Flyway를 끄므로 공고 공급유형 CHECK 마이그레이션을 직접 적용해 확인한다. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class AnnouncementSupplyTypeMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 공고_공급유형_제약은_장기전세를_포함한_모든_공급유형과_옛_한글_값을_허용한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA announcement_supply_type_migration_test");
            statement.execute("SET search_path TO announcement_supply_type_migration_test");
            try {
                // V20261101_01이 남긴 제약(장기전세 없음)을 흉내 낸다.
                statement.execute("""
                        CREATE TABLE announcements (supply_type varchar(255),
                        CONSTRAINT announcements_supply_type_check CHECK (supply_type IN ('HAPPY_HOUSING', '행복주택')))
                        """);
                assertThatThrownBy(() -> statement.execute("INSERT INTO announcements VALUES ('LONG_TERM_JEONSE')"))
                        .isInstanceOf(SQLException.class);

                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261108_01__announcement_long_term_jeonse_supply_type.sql"));

                for (RentalType type : RentalType.values()) {
                    for (String value : type.storedValues()) {
                        statement.execute("INSERT INTO announcements VALUES ('" + value + "')");
                    }
                }
                assertThatThrownBy(() -> statement.execute("INSERT INTO announcements VALUES ('UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA announcement_supply_type_migration_test CASCADE");
            }
        }
    }
}
