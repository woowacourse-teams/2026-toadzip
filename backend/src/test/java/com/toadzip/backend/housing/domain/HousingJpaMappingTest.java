package com.toadzip.backend.housing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class HousingJpaMappingTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void JPA로_생성한_주택형_테이블도_단지별_조회_인덱스를_갖는다() {
        String definition = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'housing_types'
                  AND indexname = 'idx_housing_types_housing_complex_id'
                """).query(String.class).single();

        assertThat(definition).contains("USING btree (housing_complex_id)");
    }
}
