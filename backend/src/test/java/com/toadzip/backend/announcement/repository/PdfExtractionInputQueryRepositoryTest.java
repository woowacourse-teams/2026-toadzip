package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class PdfExtractionInputQueryRepositoryTest {
    private static final String SCHEMA = "pdf_extraction_input_test";

    @Autowired
    private DataSource dataSource;

    @Test
    void 공고의_가장_최근_LH_공급_원천_행과_연결_단지의_주택형만_담는다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                createTables(sql);
                sql.execute("""
                        INSERT INTO announcements VALUES (1, 'PAN-1'), (2, 'PAN-2'), (3, NULL);
                        INSERT INTO lh_announcement_query_sources VALUES
                            (10, 'LH_ANNOUNCEMENT_SUPPLY', 'PAN-1', '2026-10-01T00:00:00Z'),
                            (11, 'LH_ANNOUNCEMENT_SUPPLY', 'PAN-1', '2026-10-02T00:00:00Z'),
                            (12, 'LH_ANNOUNCEMENT_SUPPLY', 'PAN-1', '2026-10-03T00:00:00Z'),
                            (13, 'LH_ANNOUNCEMENT_DETAIL', 'PAN-1', '2026-10-04T00:00:00Z'),
                            (20, 'LH_ANNOUNCEMENT_SUPPLY', 'PAN-2', '2026-10-05T00:00:00Z');
                        INSERT INTO lh_announcement_supply_rows VALUES
                            (100, 10, 0, '예전 단지', '59A', '59.98', '80.1', '9', '9'),
                            (101, 11, 1, '부평', '36B(고령자)', '36.02', '58.898', '22', '52'),
                            (102, 11, 0, '부평', '16A(청년)', '16.37', '27.6384', '78', '78'),
                            (103, 13, 0, '상세', '99A', '99', '99', '1', '1'),
                            (104, 20, 0, '다른 공고', '46A', '46', '60', '5', '5');
                        INSERT INTO housing_complexes VALUES (7, '부평 행복주택', FALSE), (8, '휴지통 단지', TRUE),
                            (9, '다른 단지', FALSE);
                        INSERT INTO housing_types VALUES
                            (70, 7, '36B', 36.0200, 58.8980, 52, FALSE), (71, 7, '16A', 16.3700, 27.6384, 78, FALSE),
                            (72, 7, '삭제한 주택형', 20.0000, NULL, NULL, TRUE), (80, 8, '59A', 59.0000, NULL, NULL, FALSE),
                            (90, 9, '46A', 46.0000, NULL, NULL, FALSE);
                        INSERT INTO announcement_complexes VALUES (1, 7), (1, 7), (1, 8), (1, NULL), (2, 9);
                        """);
                var repository = new PdfExtractionInputQueryRepository(
                        JdbcClient.create(new SingleConnectionDataSource(connection, true)));

                PdfExtractionInput input = repository.read(1L);

                assertThat(input.lhSupplyRows()).containsExactly(
                        new PdfExtractionInput.LhSupplyRow(0, "부평", "16A(청년)", "16.37", "27.6384", "78", "78"),
                        new PdfExtractionInput.LhSupplyRow(1, "부평", "36B(고령자)", "36.02", "58.898", "22", "52"));
                assertThat(input.housingTypes()).containsExactly(
                        new PdfExtractionInput.HousingTypeCandidate(71L, 7L, "부평 행복주택", "16A",
                                new BigDecimal("16.3700"), new BigDecimal("27.6384"), 78),
                        new PdfExtractionInput.HousingTypeCandidate(70L, 7L, "부평 행복주택", "36B",
                                new BigDecimal("36.0200"), new BigDecimal("58.8980"), 52));
                assertThat(repository.read(3L).lhSupplyRows()).isEmpty();
            }
            finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    private static void createTables(Statement sql) throws Exception {
        sql.execute("""
                CREATE TABLE announcements (id BIGINT PRIMARY KEY, lh_pan_id VARCHAR(100));
                CREATE TABLE lh_announcement_query_sources (
                    id BIGINT PRIMARY KEY, source VARCHAR(50), pan_id VARCHAR(100), collected_at TIMESTAMPTZ);
                CREATE TABLE lh_announcement_supply_rows (
                    id BIGINT PRIMARY KEY, source_id BIGINT, source_order INTEGER, complex_label TEXT, type_name TEXT,
                    exclusive_area TEXT, supply_area TEXT, supplied_unit_count TEXT, total_unit_count TEXT);
                CREATE TABLE housing_complexes (id BIGINT PRIMARY KEY, name VARCHAR(255), admin_deleted BOOLEAN);
                CREATE TABLE housing_types (
                    id BIGINT PRIMARY KEY, housing_complex_id BIGINT, name VARCHAR(255), exclusive_area NUMERIC(10, 4),
                    supply_area NUMERIC(10, 4), total_household_count INTEGER, admin_deleted BOOLEAN);
                CREATE TABLE announcement_complexes (announcement_id BIGINT, housing_complex_id BIGINT);
                """);
    }
}
