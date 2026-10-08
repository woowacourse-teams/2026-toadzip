package com.toadzip.backend.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class HousingTypeRentalConditionMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 원천키가_정확히_일치하고_금액이_충돌하지_않는_주택형만_채운다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA housing_type_price_migration_test");
            try {
                connection.setSchema("housing_type_price_migration_test");
                sql.execute("CREATE TABLE housing_types (id bigint PRIMARY KEY, source_housing_type_identifier text)");
                sql.execute("""
                        CREATE TABLE myhome_complex_source_rows (
                            hsmp_sn bigint, pnu text, suply_ty_nm text, style_nm text,
                            suply_prvuse_ar numeric, suply_cmnuse_ar numeric,
                            bass_rent_gtn bigint, bass_mt_rntchrg bigint, collected_at timestamptz)
                        """);
                sql.execute("""
                        INSERT INTO housing_types VALUES
                            (1, '1:13:pnu4:국민임대2:292:291:5'),
                            (2, '1:13:pnu4:국민임대3:29S2:291:5'),
                            (3, '1:13:pnu4:국민임대2:462:461:5'),
                            (4, '1:13:pnu4:국민임대2:592:591:5'),
                            (5, 'unmatched')
                        """);
                sql.execute("""
                        INSERT INTO myhome_complex_source_rows VALUES
                            (1, 'pnu', '국민임대', '29', 29.0000, 5, 10000000, 0, '2026-10-05T10:40:16Z'),
                            (1, 'pnu', '국민임대', '29', 29.0000, 5, 10000000, 0, '2026-10-05T10:40:17Z'),
                            (1, 'pnu', '국민임대', '29S', 29.0000, 5, 20000000, 110000, '2026-10-05T10:40:16Z'),
                            (1, 'pnu', '국민임대', '46', 46, 5, 30000000, 200000, '2026-10-05T10:40:16Z'),
                            (1, 'pnu', '국민임대', '46', 46, 5, 31000000, 200000, '2026-10-05T10:40:16Z'),
                            (1, 'pnu', '국민임대', '59', 59, 5, null, -1, '2026-10-05T10:40:16Z')
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261008_02__housing_type_basic_rental_conditions.sql"));
                try (var rows = sql.executeQuery("SELECT * FROM housing_types ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("basic_deposit")).isEqualTo(10_000_000L);
                    assertThat(rows.getObject("basic_monthly_rent")).isEqualTo(0L);
                    assertThat(rows.getTimestamp("rental_condition_collected_at").toInstant().toString())
                            .isEqualTo("2026-10-05T10:40:17Z");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("basic_deposit")).isEqualTo(20_000_000L);
                    assertThat(rows.getLong("basic_monthly_rent")).isEqualTo(110_000L);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("basic_deposit")).isNull();
                    assertThat(rows.getTimestamp("rental_condition_collected_at")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("basic_deposit")).isNull();
                    assertThat(rows.getObject("basic_monthly_rent")).isNull();
                    assertThat(rows.getTimestamp("rental_condition_collected_at")).isNotNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getTimestamp("rental_condition_collected_at")).isNull();
                }
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA housing_type_price_migration_test CASCADE");
            }
        }
    }
}
