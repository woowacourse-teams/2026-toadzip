package com.toadzip.backend.ingest.collection.history.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceRows;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class LegacySourceImportMigrationTest {

    @Test
    void importsAllSixSourcesAndPreservesUnknownTimeLifecycleAndExactQueryConditions() throws Exception {
        withLegacyDatabase(connection -> {
            String description = "PAN_ID=PAN1&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=062"
                    + "&AIS_TP_CD=13&COLLECTION_VERSION=6";
            String hash = LhAnnouncementQuery.requestHashOf(description);
            try (Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO myhome_complex_source(source_key, hsmp_sn, brtc_code, signgu_code, rn_adres)
                        VALUES ('1:1-1:-1:-1:-1:-1:', 1, '11', '680', '주소');
                        INSERT INTO myhome_announcement_source(source_key, pblanc_id, house_sn,
                            active, consecutive_miss_count, last_seen_run_id)
                        VALUES ('4:ANN11:1', 'ANN1', 1, false, 2, 'old-run');
                        INSERT INTO lh_catalog_source(source_order, complex_label) VALUES (0, '단지');
                        INSERT INTO lh_announcement_catalog_source(source_key, pan_id, connection_system_division_code,
                            upper_announcement_type_code, announcement_type_code, supply_info_type_code,
                            content_fingerprint, raw_payload, changed_at, collected_at, present_in_latest_catalog)
                        VALUES ('03:06:13:PAN1', 'PAN1', '03', '06', '13', '062', repeat('a', 64),
                            '{"PAN_NM":"공고","DTL_URL_MOB":"https://mobile.example.com"}',
                            '2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z', true);
                        """);
                for (String kind : new String[] {"SUPPLY", "DETAIL"}) {
                    sql.execute("INSERT INTO lh_announcement_collection_checkpoints(source, pan_id, request_hash, "
                            + "request_description, source_announcement_key, completed_at) VALUES ('LH_ANNOUNCEMENT_"
                            + kind + "', 'PAN1', '" + hash + "', '" + description + "', 'old', now())");
                }
                sql.execute("INSERT INTO lh_announcement_supply_source(pan_id, request_hash, source_order, "
                        + "complex_label, supplied_unit_count) VALUES ('PAN1', '" + hash + "', 0, '단지', '2')");
                sql.execute("INSERT INTO lh_announcement_detail_source(pan_id, request_hash, source_order, "
                        + "dataset_type, name, url) VALUES ('PAN1', '" + hash + "', 0, 'ATTACHMENT', '첨부', 'https://a')");
            }
            migrate(connection);
            assertThat(number(connection, "SELECT COUNT(*) FROM source_legacy_import_report "
                    + "WHERE imported_row_count = 1 AND preserved_legacy_row_count = 0")).isEqualTo(6);
            assertThat(number(connection, "SELECT COUNT(*) FROM source_collection_records "
                    + "WHERE status = 'IMPORTED'")).isEqualTo(6);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_announcement_source_rows "
                    + "WHERE collected_at IS NULL AND request_supply_type_code IS NULL "
                    + "AND active = false AND consecutive_miss_count = 2 AND last_seen_run_id = 'old-run'")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_query_sources "
                    + "WHERE collected_at IS NULL")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_catalog_entries "
                    + "WHERE query_start_date IS NULL AND query_end_date IS NULL "
                    + "AND mobile_detail_url = 'https://mobile.example.com'")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_query_parameters "
                    + "WHERE parameter_name = 'AIS_TP_CD' AND parameter_value = '13'")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source")).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM myhome_complex_source legacy
                    JOIN (%s) current ON current.source_key = legacy.source_key
                    """.formatted(IngestSourceRows.query(IngestSourceCategory.MYHOME_COMPLEX)))).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM myhome_announcement_source legacy
                    JOIN (%s) current ON current.source_key = legacy.source_key
                    """.formatted(IngestSourceRows.query(IngestSourceCategory.MYHOME_ANNOUNCEMENT)))).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_query_sources "
                    + "WHERE request_hash = '" + hash + "' AND request_description = '" + description + "'"))
                    .isEqualTo(2);
        });
    }

    @Test
    void holdsWholeConflictingRegionAndUnrecoverableLhQueryInOriginalTables() throws Exception {
        withLegacyDatabase(connection -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO myhome_complex_source(source_key, hsmp_sn, brtc_code, signgu_code)
                        VALUES ('a', 1, '11', '680'), ('b', 1, '26', '440'), ('c', 2, '11', '680');
                        INSERT INTO lh_announcement_supply_source(pan_id, request_hash, source_order, complex_label)
                        VALUES ('UNKNOWN', repeat('a', 64), 0, '기존');
                        """);
            }
            migrate(connection);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_regions")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_query_sources")).isZero();
            assertThat(number(connection, "SELECT preserved_legacy_row_count FROM source_legacy_import_report "
                    + "WHERE source = 'MYHOME_COMPLEX'")).isEqualTo(3);
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_supply_source")).isOne();
        });
    }

    @Test
    void preservesExistingEmptyCanonicalRegionAndHoldsMultipleVersionsOfTheSameLhQuery() throws Exception {
        withLegacyDatabase(connection -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO myhome_complex_source(source_key, hsmp_sn, brtc_code, signgu_code)
                        VALUES ('old', 1, '11', '680');
                        INSERT INTO source_collection_records(id, version, source, started_at, finished_at,
                            status, stored_row_count)
                        VALUES ('00000000-0000-0000-0000-000000000001', 0, 'MYHOME_COMPLEX',
                            now(), now(), 'SUCCESS', 0);
                        INSERT INTO myhome_complex_source_regions(version, province_code, district_code,
                            collected_at, last_collection_record_id)
                        VALUES (0, '11', '680', now(), '00000000-0000-0000-0000-000000000001');
                        INSERT INTO myhome_announcement_source(source_key, pblanc_id, house_sn)
                        VALUES ('valid', 'A1', 1), ('invalid', 'A1', NULL);
                        """);
                for (int version : new int[] {5, 6}) {
                    String description = "PAN_ID=PAN1&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=062"
                            + "&COLLECTION_VERSION=" + version;
                    String hash = LhAnnouncementQuery.requestHashOf(description);
                    sql.execute("INSERT INTO lh_announcement_collection_checkpoints(source, pan_id, request_hash, "
                            + "request_description, source_announcement_key, completed_at) VALUES "
                            + "('LH_ANNOUNCEMENT_SUPPLY', 'PAN1', '" + hash + "', '" + description + "', 'old', now())");
                    sql.execute("INSERT INTO lh_announcement_supply_source(pan_id, request_hash, source_order, "
                            + "complex_label) VALUES ('PAN1', '" + hash + "', 0, '기존')");
                }
            }
            migrate(connection);

            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_rows")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_regions")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_announcement_source_rows")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_query_sources")).isZero();
            assertThat(number(connection, "SELECT preserved_legacy_row_count FROM source_legacy_import_report "
                    + "WHERE source = 'LH_ANNOUNCEMENT_SUPPLY'")).isEqualTo(2);
        });
    }

    @Test
    void preservesNullRegionConflictsAndRepeatedValidRows() throws Exception {
        withLegacyDatabase(connection -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO myhome_complex_source(source_key, hsmp_sn, brtc_code, signgu_code)
                        VALUES ('conflict', 1, '11', '680'), ('unknown-region', 1, NULL, '680'),
                            ('blocked-sibling', 2, '11', '680'), ('valid1', 3, '26', '440'),
                            ('valid2', 3, '26', '440'), ('invalid-id', NULL, '41', '111'),
                            ('invalid-sibling', 4, '41', '111'), ('existing-bundle', 10, '42', '222'),
                            ('existing-bundle-sibling', 11, '42', '222');
                        INSERT INTO source_collection_records
                            (id, version, source, started_at, finished_at, status, stored_row_count)
                        VALUES ('00000000-0000-0000-0000-000000000001', 0, 'MYHOME_COMPLEX',
                            now(), now(), 'SUCCESS', 0);
                        INSERT INTO myhome_complex_source_regions
                            (id, version, province_code, district_code, collected_at, last_collection_record_id)
                        VALUES (101, 0, '31', '001', now(), '00000000-0000-0000-0000-000000000001');
                        INSERT INTO myhome_complex_source_bundles(version, hsmp_sn, region_id)
                        VALUES (0, 10, 101);
                        INSERT INTO myhome_announcement_source(source_key, pblanc_id, house_sn)
                        VALUES ('invalid-announcement', ' ANN1 ', NULL), ('blocked-announcement', 'ANN1', 1),
                            ('valid-announcement', 'ANN2', 0);
                        """);
            }
            migrate(connection);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_rows")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_rows "
                    + "WHERE hsmp_sn = 3 AND source_order IN (0, 1)")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_regions")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_announcement_source_rows "
                    + "WHERE pblanc_id = 'ANN2' AND house_sn = 0")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_announcement_source_rows")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source")).isEqualTo(9);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_announcement_source")).isEqualTo(3);
        });
    }

    @Test
    void imports130000ComplexRowsWithBoundedStatementTime() throws Exception {
        withLegacyDatabase(connection -> {
            try (Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO myhome_complex_source(source_key, hsmp_sn, brtc_code, signgu_code, collected_at)
                        SELECT 'bulk-' || serial, serial, '11', '680', '2026-10-01T00:00:00Z'::timestamptz
                        FROM generate_series(1, 130000) serial;
                        ANALYZE myhome_complex_source;
                        """);
            }
            long startedAt = System.nanoTime();
            Flyway.configure().dataSource(connection.getMetaData().getURL(), "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").target("20261004.07")
                    .initSql("SET statement_timeout = '30s'").load().migrate();
            System.out.println("130000-row legacy import took "
                    + (System.nanoTime() - startedAt) / 1_000_000 + " ms");
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_rows")).isEqualTo(130000);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_bundles")).isEqualTo(130000);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source_rows "
                    + "WHERE collected_at = '2026-10-01T00:00:00Z'::timestamptz")).isEqualTo(130000);
            assertThat(number(connection, "SELECT imported_row_count FROM source_legacy_import_report "
                    + "WHERE source = 'MYHOME_COMPLEX'")).isEqualTo(130000);
            assertThat(number(connection, "SELECT COUNT(*) FROM myhome_complex_source")).isEqualTo(130000);
        });
    }

    private void withLegacyDatabase(SqlWork work) throws Exception {
        String name = "source_import_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + name);
            try {
                Flyway.configure().dataSource(url(name), "toadzip_test", "toadzip_test")
                        .locations("classpath:db/migration").target("20261004.06").load().migrate();
                try (Connection connection = connect(name)) {
                    work.run(connection);
                }
            } finally {
                sql.execute("DROP DATABASE " + name + " WITH (FORCE)");
            }
        }
    }

    private void migrate(Connection connection) throws Exception {
        Flyway.configure().dataSource(connection.getMetaData().getURL(), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration").target("20261004.07").load().migrate();
    }

    private long number(Connection connection, String query) throws Exception {
        try (Statement sql = connection.createStatement(); var result = sql.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private Connection connect(String database) throws Exception {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        String port = System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432");
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
