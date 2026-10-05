package com.toadzip.backend.ingest.collection.history.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DiscardCollectedSourcesMigrationTest {

    @Test
    void 수집_원천만_폐기하고_제품과_관리자_확인과_감사_이력을_보존한다() throws Exception {
        String name = "source_discard_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + name);
            try {
                migration(name).target("20261004.07").load().migrate();
                try (Connection connection = connect(name); Statement seed = connection.createStatement()) {
                    seed.execute("""
                            CREATE TABLE myhome_announcement_collection_runs
                                (id UUID PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL);
                            INSERT INTO myhome_announcement_collection_runs VALUES
                                ('00000000-0000-0000-0000-000000000001', now());
                            INSERT INTO source_collection_records
                                (id, version, source, started_at, finished_at, status, stored_row_count)
                            SELECT ('00000000-0000-0000-0000-00000000000' || n)::uuid,
                                   0, source, now(), now(),
                                   CASE WHEN n = 1 THEN 'IMPORTED' ELSE 'SUCCESS' END, 1
                            FROM (VALUES (1, 'MYHOME_ANNOUNCEMENT'), (2, 'MYHOME_COMPLEX'),
                                 (3, 'LH_LEASE_CATALOG'), (4, 'LH_ANNOUNCEMENT_CATALOG'),
                                 (5, 'LH_ANNOUNCEMENT_SUPPLY'), (6, 'LH_ANNOUNCEMENT_DETAIL')) sources(n, source);
                            INSERT INTO source_collection_record_parameters
                            SELECT id, 'fixture', 'before-cleanup' FROM source_collection_records;
                            INSERT INTO myhome_announcement_source_bundles
                                (id, version, pblanc_id, last_collection_record_id)
                            VALUES (101, 0, 'ANN1', '00000000-0000-0000-0000-000000000001');
                            INSERT INTO myhome_announcement_source_rows
                                (source_id, collection_record_id, request_supply_type_code,
                                 collected_at, source_order, pblanc_id, house_sn)
                            VALUES (101, '00000000-0000-0000-0000-000000000001', '01', now(), 0, 'ANN1', 1);
                            INSERT INTO myhome_complex_source_regions
                                (id, version, province_code, district_code, collected_at, last_collection_record_id)
                            VALUES (102, 0, '11', '680', now(), '00000000-0000-0000-0000-000000000002');
                            INSERT INTO myhome_complex_source_bundles (id, version, hsmp_sn, region_id)
                            VALUES (102, 0, 1, 102);
                            INSERT INTO myhome_complex_source_rows(source_id, source_order, hsmp_sn)
                            VALUES (102, 0, 1);
                            INSERT INTO lh_lease_catalog_source_bundles
                                (id, version, scope_key, collected_at, last_collection_record_id)
                            VALUES (103, 0, 'ALL', now(), '00000000-0000-0000-0000-000000000003');
                            INSERT INTO lh_lease_catalog_source_rows(source_id, source_order) VALUES (103, 0);
                            INSERT INTO lh_announcement_catalog_entries
                                (source_key, raw_payload, query_start_date, query_end_date, changed_at,
                                 collected_at, present_in_latest_catalog, last_collection_record_id)
                            VALUES ('03:06:13:PAN1', '{}', '20261001', '20261005', now(), now(), true,
                                    '00000000-0000-0000-0000-000000000004');
                            INSERT INTO lh_announcement_query_sources
                                (id, source, pan_id, query_hash, request_hash, request_description,
                                 collected_at, verified_empty, last_collection_record_id)
                            VALUES
                                (105, 'LH_ANNOUNCEMENT_SUPPLY', 'PAN1', repeat('a',64), repeat('a',64),
                                 'supply-request', now(), false, '00000000-0000-0000-0000-000000000005'),
                                (106, 'LH_ANNOUNCEMENT_DETAIL', 'PAN1', repeat('b',64), repeat('b',64),
                                 'detail-request', now(), false, '00000000-0000-0000-0000-000000000006');
                            INSERT INTO lh_announcement_query_parameters VALUES
                                (105, 'PAN_ID', 'PAN1'), (106, 'PAN_ID', 'PAN1');
                            INSERT INTO lh_announcement_supply_rows(source_id, source_order) VALUES (105, 0);
                            INSERT INTO lh_announcement_detail_rows(source_id, source_order) VALUES (106, 0);
                            INSERT INTO myhome_announcement_lifecycle_runs VALUES
                                ('00000000-0000-0000-0000-000000000001', now());
                            INSERT INTO lh_announcement_collection_links
                                (source, pan_id, request_hash, request_description,
                                 source_announcement_key, completed_at)
                            VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'PAN1', repeat('a',64), 'supply-request', 'ANN1', now());
                            UPDATE ingest_execution_ownership SET generation = 7;
                            INSERT INTO myhome_complex_source(source_key, hsmp_sn) VALUES ('legacy', 1);
                            INSERT INTO myhome_announcement_source(source_key, pblanc_id) VALUES ('legacy', 'ANN1');
                            INSERT INTO lh_catalog_source(source_order) VALUES (0);
                            INSERT INTO lh_announcement_detail_source(source_order, pan_id) VALUES (0, 'PAN1');
                            INSERT INTO lh_announcement_supply_source(source_order, pan_id) VALUES (0, 'PAN1');
                            INSERT INTO lh_announcement_catalog_source
                                (source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
                                 announcement_type_code, supply_info_type_code, content_fingerprint, raw_payload,
                                 changed_at, collected_at, present_in_latest_catalog)
                            VALUES ('legacy', 'PAN1', '03', '06', '13', '062', repeat('a',64), '{}', now(), now(), true);
                            INSERT INTO lh_announcement_collection_checkpoints
                                (source, pan_id, request_hash, request_description, source_announcement_key, completed_at)
                            VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'PAN1', repeat('a',64), 'supply-request', 'ANN1', now());
                            INSERT INTO announcements
                                (id, application_end_date, application_start_date, name, original_url, posted_date,
                                 provider, recruitment_type, source_announcement_identifier, status, supply_type,
                                 view_count, winner_announcement_date, admin_modified, application_schedule_reviewed)
                            VALUES (201, '2026-10-31', '2026-10-01', '관리자 확인 공고', 'https://example.com',
                                    '2026-10-01', 'LH', 'NEW', 'ANN1', 'ORIGINAL', 'HAPPY_HOUSING', 0,
                                    '2026-11-01', true, true);
                            INSERT INTO supply_rows
                                (id, display_order, source_complex_name, source_housing_type_name,
                                 source_supply_row_identifier, supply_category, supply_pnu, announcement_id,
                                 admin_modified)
                            VALUES (202, 0, '단지', '유형', 'ANN1:1', 'NEW_SUPPLY', '111', 201, true);
                            INSERT INTO supply_targets
                                (id, display_order, target, supply_row_id, rental_deposit, monthly_rent,
                                 lh_amount_preserved_reason)
                            VALUES (203, 0, '일반', 202, 10000000, 100000, 'ADMIN_CONFIRMED');
                            INSERT INTO verified_lh_supply_replacements
                                (id, request_hash, proposed_fingerprint, evidence_url, reason, approved_by,
                                 approved_at, consumed_at, revoked_at)
                            VALUES (301, repeat('a',64), repeat('b',64), 'https://example.com', '미사용', 'admin',
                                    now(), NULL, NULL),
                                   (302, repeat('a',64), repeat('b',64), 'https://example.com', '소비', 'admin',
                                    now(), now(), NULL),
                                   (303, repeat('a',64), repeat('b',64), 'https://example.com', '취소', 'admin',
                                    now(), NULL, now());
                            INSERT INTO external_data_collection_failures
                                (error_type, occurred_at, reason, request_description, source, status,
                                 last_occurred_at, occurrence_count, recurrence_count, first_execution_id)
                            SELECT error_type, now(), '원래 사유', 'request-' || n, 'LH_ANNOUNCEMENT_SUPPLY',
                                   status, now(), 1, 0, '00000000-0000-0000-0000-000000000001'
                            FROM (VALUES (1, 'IncompleteLhSupplyReplacementException', 'PENDING'),
                                 (2, 'EmptyLhSupplyReplacementException', 'PENDING'),
                                 (3, 'ExternalDataRequestException', 'PENDING'),
                                 (4, 'EmptyLhSupplyReplacementException', 'RESOLVED')) failures(n, error_type, status);
                            """);
                    long previousSequence = number(connection,
                            "SELECT nextval('myhome_complex_source_rows_id_seq')");
                    var flyway = migration(name).load();
                    flyway.migrate();
                    flyway.validate();
                    for (String table : List.of("myhome_complex_source", "myhome_announcement_source",
                            "lh_catalog_source", "lh_announcement_catalog_source", "lh_announcement_detail_source",
                            "lh_announcement_supply_source", "lh_announcement_collection_checkpoints",
                            "source_legacy_import_report", "myhome_announcement_collection_runs")) {
                        assertThat(number(connection, "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name = '" + table + "'")).isZero();
                    }
                    for (String table : List.of("myhome_announcement_source_rows", "myhome_complex_source_rows",
                            "lh_lease_catalog_source_rows", "lh_announcement_supply_rows", "lh_announcement_detail_rows",
                            "lh_announcement_query_parameters", "myhome_announcement_source_bundles",
                            "myhome_complex_source_bundles", "myhome_complex_source_regions",
                            "lh_lease_catalog_source_bundles", "lh_announcement_catalog_entries",
                            "lh_announcement_query_sources", "lh_announcement_collection_links",
                            "myhome_announcement_lifecycle_runs")) {
                        assertThat(number(connection, "SELECT COUNT(*) FROM " + table)).isZero();
                    }
                    assertThat(number(connection, "SELECT last_value FROM myhome_complex_source_rows_id_seq"))
                            .isEqualTo(previousSequence);
                    assertThat(number(connection, "SELECT COUNT(*) FROM source_collection_records")).isEqualTo(6);
                    assertThat(number(connection, "SELECT COUNT(*) FROM source_collection_record_parameters"))
                            .isEqualTo(6);
                    assertThat(number(connection, "SELECT COUNT(*) FROM announcements WHERE admin_modified "
                            + "AND application_schedule_reviewed AND source_announcement_identifier = 'ANN1'")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM supply_rows WHERE admin_modified")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM supply_targets WHERE rental_deposit = 10000000 "
                            + "AND monthly_rent = 100000 AND lh_amount_preserved_reason = 'ADMIN_CONFIRMED'")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM verified_lh_supply_replacements")).isEqualTo(3);
                    assertThat(number(connection, "SELECT COUNT(*) FROM verified_lh_supply_replacements "
                            + "WHERE id = 301 AND revoked_at IS NOT NULL AND consumed_at IS NULL")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM verified_lh_supply_replacements "
                            + "WHERE id = 302 AND consumed_at IS NOT NULL AND revoked_at IS NULL")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM external_data_collection_failures "
                            + "WHERE status = 'SKIPPED' AND resolved_at IS NOT NULL AND reason = '원래 사유' "
                            + "AND first_execution_id = '00000000-0000-0000-0000-000000000001'")).isEqualTo(2);
                    assertThat(number(connection, "SELECT COUNT(*) FROM external_data_collection_failures "
                            + "WHERE status = 'PENDING' AND error_type = 'ExternalDataRequestException'")).isOne();
                    assertThat(number(connection, "SELECT COUNT(*) FROM external_data_collection_failures "
                            + "WHERE status = 'RESOLVED'")).isOne();
                    assertThat(number(connection, "SELECT generation FROM ingest_execution_ownership WHERE id = 1"))
                            .isEqualTo(7);
                    assertThat(number(connection, "SELECT COUNT(*) FROM lh_announcement_catalog_write_lock")).isOne();
                }
            } finally {
                sql.execute("DROP DATABASE " + name + " WITH (FORCE)");
            }
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration migration(String name) {
        return Flyway.configure().dataSource(url(name), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration");
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
}
