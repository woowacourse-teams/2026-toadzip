package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 테스트 프로필은 Flyway를 끄므로 공고 접수처를 별도 표로 옮기는 마이그레이션을 직접 적용해 확인한다. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class AnnouncementReceptionPlacesMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 공고의_접수처를_첫_접수처로_옮기고_옛_칸을_지운다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA announcement_reception_places_migration_test");
            statement.execute("SET search_path TO announcement_reception_places_migration_test");
            try {
                // 옮기기 전 공고 표의 접수처 칸과 제약을 흉내 낸다.
                statement.execute("""
                        CREATE TABLE announcements (id bigint PRIMARY KEY, reception_address varchar(255),
                        reception_contact varchar(255), reception_method varchar(255), reception_place_name varchar(255),
                        reception_url varchar(255),
                        CONSTRAINT announcements_reception_method_check CHECK (reception_method IN ('인터넷', 'ONLINE', '현장', 'VISIT')))
                        """);
                statement.execute("""
                        INSERT INTO announcements VALUES
                        (1, NULL, '1600-1004', 'ONLINE', 'LH 청약플러스', 'https://apply.lh.or.kr'),
                        (2, '서울시 강남구 1', NULL, '현장', '견본주택', NULL),
                        (3, NULL, NULL, NULL, NULL, NULL)
                        """);

                statement.execute(migration());

                List<String> moved = new ArrayList<>();
                try (var rows = statement.executeQuery("""
                        SELECT announcement_id, display_order, name, method, address, contact, url, guidance
                        FROM announcement_reception_places ORDER BY announcement_id
                        """)) {
                    while (rows.next()) {
                        moved.add(rows.getLong(1) + "|" + rows.getInt(2) + "|" + rows.getString(3) + "|" + rows.getString(4)
                                + "|" + rows.getString(5) + "|" + rows.getString(6) + "|" + rows.getString(7) + "|" + rows.getString(8));
                    }
                }
                assertThat(moved).containsExactly(
                        "1|0|LH 청약플러스|ONLINE|null|1600-1004|https://apply.lh.or.kr|null",
                        "2|0|견본주택|현장|서울시 강남구 1|null|null|null");
                try (var columns = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'announcement_reception_places_migration_test'
                          AND table_name = 'announcements' AND column_name LIKE 'reception%'
                        """)) {
                    columns.next();
                    assertThat(columns.getInt(1)).isZero();
                }
                // 같은 공고의 두 번째 접수처는 들어가고, 모르는 방식과 같은 순번은 막는다.
                statement.execute("INSERT INTO announcement_reception_places (announcement_id, display_order, name, method) VALUES (1, 1, '현장', 'VISIT')");
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO announcement_reception_places (announcement_id, display_order, name, method) VALUES (1, 2, '?', 'UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO announcement_reception_places (announcement_id, display_order, name, method) VALUES (1, 1, '중복', 'VISIT')"))
                        .isInstanceOf(SQLException.class);
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA announcement_reception_places_migration_test CASCADE");
            }
        }
    }

    @Test
    void 이름이나_방식이_빠진_옛_접수처가_있으면_옛_칸을_지우지_않고_멈춘다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA announcement_reception_places_partial_test");
            statement.execute("SET search_path TO announcement_reception_places_partial_test");
            try {
                statement.execute("""
                        CREATE TABLE announcements (id bigint PRIMARY KEY, reception_address varchar(255),
                        reception_contact varchar(255), reception_method varchar(255), reception_place_name varchar(255),
                        reception_url varchar(255))
                        """);
                statement.execute("INSERT INTO announcements VALUES (1, NULL, '1600-1004', NULL, 'LH 청약플러스', NULL)");

                assertThatThrownBy(() -> statement.execute(migration())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("접수처를 옮길 수 없습니다");
                // 이름·방식 없이 연락처만 남은 행도 옮길 수 없어 멈춘다.
                statement.execute("UPDATE announcements SET reception_place_name = NULL WHERE id = 1");
                assertThatThrownBy(() -> statement.execute(migration())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("접수처를 옮길 수 없습니다");
                try (var rows = statement.executeQuery("SELECT reception_contact FROM announcements WHERE id = 1")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("1600-1004");
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA announcement_reception_places_partial_test CASCADE");
            }
        }
    }

    /** DO 블록($$)이 있어 문장을 나누지 않고 PostgreSQL 드라이버에 한 번에 보낸다. */
    private String migration() throws Exception {
        try (var input = new ClassPathResource("db/migration/V20261107_04__announcement_reception_places.sql").getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
