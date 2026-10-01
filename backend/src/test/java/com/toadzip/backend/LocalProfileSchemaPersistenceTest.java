package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class LocalProfileSchemaPersistenceTest {

    @Test
    void local_프로필은_Flyway로_스키마를_생성하고_종료_후에도_유지한다() throws Exception {
        String databaseName = "toadzip_local_profile_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = primaryTestDatabaseUrl(databaseName);
        createDatabase(databaseName);

        try {
            try (ConfigurableApplicationContext applicationContext =
                    new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run()) {
                String ddlAuto = applicationContext.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto");
                assertEquals("validate", ddlAuto);
                verifyFailurePersistence(applicationContext);
            }

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    ResultSet tables = connection.getMetaData()
                            .getTables(null, null, "announcements", new String[] {"TABLE"});
                    Statement statement = connection.createStatement();
                    ResultSet history = statement.executeQuery(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success"
                    )) {
                assertAll(
                        () -> assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName()),
                        () -> assertTrue(tables.next()),
                        () -> assertTrue(history.next()),
                        () -> assertEquals(29, history.getInt(1)),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_interest_events", "event_id")),
                        () -> assertEquals(0, countColumn(connection,
                                "notification_interest_events", "email")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_subscriptions", "expires_at")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_subscriptions", "expires_at")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_subscriptions", "active")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_email_preferences", "email")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_email_preferences", "email")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_subscriptions", "active")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_cancellation_requests", "code_hash")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_cancellation_requests", "code_sent_at")),
                        () -> assertEquals(1, countColumn(connection,
                                "notification_guest_cancellation_requests", "code_sent_by")),
                        () -> assertTrue(constraintDefinition(connection,
                                "notification_interest_events_event_type_check").contains("CANCELLED")),
                        () -> assertEquals(1, countColumn(connection, "users", "email")),
                        () -> assertEquals(1, countColumn(connection, "announcement_schedules", "complex_name")),
                        () -> assertEquals(1, countColumn(connection, "housing_complexes", "deposit_min")),
                        () -> assertEquals(1, countColumn(connection, "housing_complexes", "monthly_rent_min")),
                        () -> assertEquals(1, countColumn(connection,
                                "announcement_views", "viewed_on")),
                        () -> assertEquals(1, countColumn(connection,
                                "admin_announcement_imports", "original_json")),
                        () -> assertEquals(1, countColumn(connection,
                                "lh_announcement_detail_source", "request_hash")),
                        () -> assertEquals(1, countColumn(connection,
                                "lh_announcement_supply_source", "request_hash")),
                        () -> assertEquals(1, countColumn(connection,
                                "lh_announcement_catalog_source", "present_in_latest_catalog"))
                );
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    private void verifyFailurePersistence(ConfigurableApplicationContext context) {
        Instant occurredAt = Instant.parse("2026-09-29T00:00:00Z");
        var complexRepository = context.getBean(MyHomeComplexMappingFailureRepository.class);
        verifyFailurePersistence(complexRepository, MyHomeComplexMappingFailure.create(
                "complex", "complex-id", MyHomeComplexMappingFailureReason.INVALID_VALUE, "실패", occurredAt
        ), () -> complexRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                IngestFailureStatus.PENDING, PageRequest.of(0, 10)));
        var announcementRepository = context.getBean(MyHomeAnnouncementMappingFailureRepository.class);
        verifyFailurePersistence(announcementRepository, MyHomeAnnouncementMappingFailure.create(
                "announcement", "announcement-id", 1, MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                "실패", occurredAt
        ), () -> announcementRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                IngestFailureStatus.PENDING, PageRequest.of(0, 10)));
        var enrichmentRepository = context.getBean(LhAnnouncementEnrichmentFailureRepository.class);
        verifyFailurePersistence(enrichmentRepository, LhAnnouncementEnrichmentFailure.create(
                "enrichment", "announcement-id", "pan-id", LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                "실패", occurredAt
        ), () -> enrichmentRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                IngestFailureStatus.PENDING, PageRequest.of(0, 10)));
        var householdRepository = context.getBean(LhHouseholdEnrichmentFailureRepository.class);
        verifyFailurePersistence(householdRepository, LhHouseholdEnrichmentFailure.create(
                "household", "서울", "국민임대", "단지", LhHouseholdEnrichmentFailureReason.INVALID_SOURCE,
                "실패", occurredAt
        ), () -> householdRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                IngestFailureStatus.PENDING, PageRequest.of(0, 10)));
    }

    private <T extends IngestFailure<T>> void verifyFailurePersistence(
            JpaRepository<T, Long> repository, T observed, Supplier<List<T>> pendingQuery
    ) {
        UUID firstExecution = UUID.randomUUID();
        UUID resolvedExecution = UUID.randomUUID();
        observed.attachFirstExecution(firstExecution);
        T stored = repository.saveAndFlush(observed);
        Long id = stored.getId();
        T reloaded = repository.findById(id).orElseThrow();
        assertEquals(observed.getSourceKey(), reloaded.getSourceKey());
        assertEquals(observed.getReason(), reloaded.getReason());
        assertEquals(firstExecution, reloaded.getFirstExecutionId());
        Instant resolvedAt = observed.getOccurredAt().plusSeconds(60);
        reloaded.resolve(resolvedAt, resolvedExecution);
        repository.saveAndFlush(reloaded);
        assertTrue(pendingQuery.get().isEmpty());

        T resolved = repository.findById(id).orElseThrow();
        resolved.observe(observed, null);
        repository.saveAndFlush(resolved);
        List<T> pending = pendingQuery.get();
        assertEquals(1, pending.size());
        T recurred = pending.getFirst();
        assertEquals(id, recurred.getId());
        assertEquals(2, recurred.getOccurrenceCount());
        assertEquals(1, recurred.getRecurrenceCount());
        assertEquals(observed.getOccurredAt(), recurred.getOccurredAt());
        assertEquals(resolvedAt, recurred.getLastResolvedAt());
        assertEquals(resolvedExecution, recurred.getLastResolvedExecutionId());
    }

    @Test
    void 통합_유형을_먼저_적용한_DB에_develop_마이그레이션을_보충한다(
            @TempDir Path branchMigrations
    ) throws Exception {
        Set<String> developMigrations = Set.of(
                "V20260928_03__announcement_daily_views.sql",
                "V20260928_04__verified_lh_supply_replacements.sql",
                "V20260929_01__announcement_schedule_complex_name.sql"
        );
        try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : migrations.toList()) {
                String migrationName = migration.getFileName().toString();
                if (developMigrations.contains(migrationName) || migrationName.compareTo("V20260930_02") >= 0) {
                    continue;
                }
                Files.copy(migration, branchMigrations.resolve(migration.getFileName()));
            }
        }
        String databaseName = "toadzip_branch_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = primaryTestDatabaseUrl(databaseName);
        createDatabase(databaseName);
        try {
            Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("filesystem:" + branchMigrations).load().migrate();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO data_pipeline_executions
                            (execution_id, type, status, started_at, heartbeat_at)
                        VALUES ('00000000-0000-0000-0000-000000000001',
                                'COMPLEX_SYNC', 'COMPLETED', now(), now())
                        """);
                assertEquals(18, countAllRows(connection, "flyway_schema_history"));
            }

            Flyway merged = Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").load();
            FlywayValidateException pending = assertThrows(FlywayValidateException.class, merged::migrate);
            for (String version : List.of("20260928.03", "20260928.04", "20260929.01")) {
                assertTrue(pending.getMessage().contains(version));
            }
            try (ConfigurableApplicationContext oneTimeUpgrade = new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run("--spring.flyway.out-of-order=true", "--spring.flyway.target=20260930.09")) {
                assertTrue(oneTimeUpgrade.getBean(Flyway.class).getConfiguration().isOutOfOrder());
            }
            merged.validate();
            assertEquals(0, merged.migrate().migrationsExecuted);

            try (ConfigurableApplicationContext applicationContext =
                    new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl)).run();
                    Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test")) {
                assertEquals(29, countAllRows(connection, "flyway_schema_history"));
                assertEquals(1, countAllRows(connection, "data_pipeline_executions"));
                assertEquals(1, countColumn(connection, "announcement_schedules", "complex_name"));
                assertEquals(1, countColumn(connection, "supply_targets", "lh_amount_preserved_reason"));
                assertEquals("validate", applicationContext.getEnvironment()
                        .getProperty("spring.jpa.hibernate.ddl-auto"));
                assertFalse(applicationContext.getBean(Flyway.class).getConfiguration().isOutOfOrder());
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    @Test
    void 최신_develop_DB에_통합_실행_유형을_보충하고_기존_이력을_보존한다(
            @TempDir Path developMigrations
    ) throws Exception {
        try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : migrations.toList()) {
                if (migration.getFileName().toString().equals("V20260930_01__allow_combined_ingest_pipelines.sql")) {
                    continue;
                }
                Files.copy(migration, developMigrations.resolve(migration.getFileName()));
            }
        }
        String databaseName = "toadzip_develop_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = primaryTestDatabaseUrl(databaseName);
        createDatabase(databaseName);
        try {
            Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("filesystem:" + developMigrations).load().migrate();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO data_pipeline_executions
                            (execution_id, type, status, started_at, heartbeat_at)
                        VALUES ('00000000-0000-0000-0000-000000000001',
                                'COMPLEX_COLLECTION', 'COMPLETED', now(), now())
                        """);
                assertEquals(28, countAllRows(connection, "flyway_schema_history"));
            }
            Flyway merged = Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").load();
            FlywayValidateException pending = assertThrows(FlywayValidateException.class, merged::migrate);
            assertTrue(pending.getMessage().contains("20260930.01"));
            try (ConfigurableApplicationContext oneTimeUpgrade = new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run("--spring.flyway.out-of-order=true", "--spring.flyway.target=20260930.09")) {
                assertTrue(oneTimeUpgrade.getBean(Flyway.class).getConfiguration().isOutOfOrder());
            }
            merged.validate();
            assertEquals(0, merged.migrate().migrationsExecuted);
            try (ConfigurableApplicationContext applicationContext =
                    new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl)).run();
                    Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                assertEquals(29, countAllRows(connection, "flyway_schema_history"));
                assertEquals(1, countAllRows(connection, "data_pipeline_executions"));
                assertFalse(applicationContext.getBean(Flyway.class).getConfiguration().isOutOfOrder());
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO data_pipeline_executions
                            (execution_id, type, status, started_at, heartbeat_at)
                        VALUES ('00000000-0000-0000-0000-000000000002',
                                'COMPLEX_SYNC', 'COMPLETED', now(), now())
                        """));
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    @Test
    void 기존_스키마를_자동_baseline_후_통합_마이그레이션으로_보정한다() throws Exception {
        String databaseName = "toadzip_reconciliation_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = primaryTestDatabaseUrl(databaseName);
        createDatabase(databaseName);

        try {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                ScriptUtils.executeSqlScript(connection, MigrationSqlSection.productionSchemaSnapshot());
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_detail_source (pan_id, source_order, dataset_type)
                        VALUES ('legacy-pan', 0, 'ETC_INFO')
                        """);
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_supply_source (pan_id, source_order)
                        VALUES ('legacy-pan', 0)
                        """);
            }

            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run()) {
                // Startup records the baseline and applies pending migrations.
            }

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_detail_source
                            (pan_id, request_hash, source_order, dataset_type)
                        VALUES ('legacy-pan', repeat('a', 64), 0, 'ETC_INFO'),
                               ('legacy-pan', repeat('b', 64), 0, 'ETC_INFO')
                        """);
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_supply_source (pan_id, request_hash, source_order)
                        VALUES ('legacy-pan', repeat('a', 64), 0),
                               ('legacy-pan', repeat('b', 64), 0)
                        """);
                statement.executeUpdate("""
                        INSERT INTO data_pipeline_executions
                            (execution_id, type, status, started_at, heartbeat_at)
                        VALUES ('00000000-0000-0000-0000-000000000001',
                                'ANNOUNCEMENT_REFINEMENT', 'COMPLETED_WARNINGS', now(), now())
                        """);
            }

            try (ConfigurableApplicationContext applicationContext =
                    new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run();
                    Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement();
                    ResultSet history = statement.executeQuery("""
                            SELECT string_agg(type || ':' || version, ',' ORDER BY installed_rank)
                            FROM flyway_schema_history
                            """)) {
                String replayRequest = new LhAnnouncementRequest(
                        "legacy-pan", "03", "06", "07", "062"
                ).requestDescription();
                LhSourceStore sourceStore = applicationContext.getBean(LhSourceStore.class);
                sourceStore.replaceDetails("legacy-pan", replayRequest, List.of(new LhAnnouncementDetailSource(
                        0, "legacy-pan", "ETC_INFO", null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, "재수집", null
                )));
                sourceStore.replaceSupplies("legacy-pan", replayRequest, List.of(
                        new LhAnnouncementSupplySource(0, "legacy-pan", new LhAnnouncementSupplySourceSnapshot(
                                "재수집 단지", "46A", "46.8", "67.0", "100", "20", null, null
                        ))
                ));
                String replayHash = LhAnnouncementCollectionCheckpoint.requestHashOf(replayRequest);
                assertEquals(1, countRequestRows(connection, "lh_announcement_detail_source", replayHash));
                assertEquals(1, countRequestRows(connection, "lh_announcement_supply_source", replayHash));
                assertTrue(history.next());
                assertEquals("BASELINE:20260922.00,SQL:20260922.01,SQL:20260922.02,SQL:20260923.01"
                                + ",SQL:20260923.02,SQL:20260924.01,SQL:20260925.01,SQL:20260925.02"
                                + ",SQL:20260925.03,SQL:20260926.01,SQL:20260926.02,SQL:20260926.03"
                                + ",SQL:20260926.04,SQL:20260926.05,SQL:20260926.06,SQL:20260927.01"
                                + ",SQL:20260928.01,SQL:20260928.02,SQL:20260928.03,SQL:20260928.04"
                                + ",SQL:20260929.01,SQL:20260930.01,SQL:20260930.02,SQL:20260930.03"
                                + ",SQL:20260930.04,SQL:20260930.05,SQL:20260930.06,SQL:20260930.07"
                                + ",SQL:20260930.08,SQL:20260930.09",
                        history.getString(1));
                assertEquals(1, countColumn(connection, "verified_lh_supply_replacements", "evidence_url"));
                assertEquals(1, countColumn(connection, "supply_targets", "lh_amount_preserved_reason"));
                assertEquals(1, countColumn(connection, "admin_announcement_imports", "original_json"));
                assertEquals(1, countColumn(connection, "notification_interest_events", "event_id"));
                assertEquals(1, countColumn(connection, "announcements", "lh_reception_place_owned"));
                assertEquals(1, countColumn(connection, "announcements", "application_schedule_reviewed"));
                assertEquals(1, countColumn(connection, "announcements", "lh_pan_id_reviewed"));
                assertEquals(1, countColumn(connection, "ingest_execution_ownership", "generation"));
                assertEquals(1, countColumn(connection, "ingest_execution_ownership", "owner_id"));
                assertEquals(1, countColumn(connection, "data_pipeline_executions", "stop_requested"));
                assertEquals(1, countColumn(connection, "data_pipeline_executions", "work_progress"));
                assertEquals(1, countColumn(connection, "data_pipeline_executions", "external_request_count"));
                assertEquals(1, countAllRows(connection, "ingest_execution_ownership"));
                assertEquals(0, countAllRows(connection, "announcement_application_schedules"));
                assertEquals(1, countColumn(connection, "myhome_complex_links", "approved_household_count"));
                assertEquals(1, countColumn(connection, "myhome_complex_merges", "before_state"));
                assertEquals(1, countColumn(connection, "housing_complex_aliases", "housing_complex_id"));
                assertEquals(1, countColumn(connection, "supply_rows", "lh_total_supply_household_count_enriched"));
                assertEquals(1, countColumn(connection, "lh_announcement_detail_source", "request_hash"));
                assertEquals(1, countColumn(connection, "lh_announcement_detail_source", "winner_announcement_date"));
                assertEquals(1, countColumn(connection, "lh_announcement_supply_source", "request_hash"));
                assertEquals(1, countLegacyRow(connection, "lh_announcement_detail_source"));
                assertEquals(1, countLegacyRow(connection, "lh_announcement_supply_source"));
                assertEquals(4, countRows(connection, "lh_announcement_detail_source"));
                assertEquals(4, countRows(connection, "lh_announcement_supply_source"));
                assertEquals("UNIQUE (pan_id, request_hash, source_order, dataset_type)",
                        constraintDefinition(connection, "uk_lh_detail_source_request_row"));
                assertEquals("UNIQUE (pan_id, request_hash, source_order)",
                        constraintDefinition(connection, "uk_lh_supply_source_request_row"));
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    @Test
    void 기존_단지와_통합_단지의_마이홈_원천_금액을_마이그레이션에서_채운다() throws Exception {
        String databaseName = "toadzip_price_backfill_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = primaryTestDatabaseUrl(databaseName);
        createDatabase(databaseName);
        try {
            Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").target("20260927.01").load().migrate();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO housing_complexes (id, city_county_district_code, latitude, legal_dong_code,
                            longitude, pnu, province_code, road_address, name, parking_space_count, provider,
                            source_complex_identifier, supply_type, total_household_count, admin_modified)
                        VALUES (1, '11110', 37.5, '1111010100', 126.9, '1111010100100010000', '11',
                            '서울 테스트로 1', '테스트 단지', 80, 'LH', '123:NATIONAL_RENTAL', 'NATIONAL_RENTAL',
                            200, TRUE)
                        """);
                statement.executeUpdate("""
                        INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id)
                        VALUES ('123:NATIONAL_RENTAL', 1), ('456:NATIONAL_RENTAL', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO myhome_complex_source
                            (source_key, hsmp_sn, suply_ty_nm, bass_rent_gtn, bass_mt_rntchrg)
                        VALUES ('price-a', 123, '국민임대', 10000000, 200000),
                               ('price-b', 123, '국민임대', 20000000, 300000),
                               ('price-c', 456, '국민임대', 30000000, 100000),
                               ('price-unknown', 456, '국민임대', -1, NULL)
                        """);
            }

            Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").target("20260928.01").load().migrate();

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement();
                    ResultSet prices = statement.executeQuery("""
                            SELECT deposit_min, deposit_max, monthly_rent_min, monthly_rent_max
                            FROM housing_complexes WHERE id = 1
                            """)) {
                assertTrue(prices.next());
                assertAll(
                        () -> assertEquals(10000000L, prices.getLong("deposit_min")),
                        () -> assertEquals(30000000L, prices.getLong("deposit_max")),
                        () -> assertEquals(100000L, prices.getLong("monthly_rent_min")),
                        () -> assertEquals(300000L, prices.getLong("monthly_rent_max"))
                );
            }

            Flyway.configure().dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").load().migrate();

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE housing_complexes
                        SET monthly_rent_min = NULL, monthly_rent_max = NULL
                        WHERE id = 1
                        """);
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        UPDATE housing_complexes SET deposit_min = NULL WHERE id = 1
                        """)).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        UPDATE housing_complexes SET deposit_max = NULL WHERE id = 1
                        """)).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        UPDATE housing_complexes SET monthly_rent_min = 100000 WHERE id = 1
                        """)).getSQLState());
                assertEquals(1, statement.executeUpdate("""
                        UPDATE housing_complexes SET deposit_min = NULL, deposit_max = NULL WHERE id = 1
                        """));
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    private int countColumn(Connection connection, String tableName, String columnName) throws Exception {
        try (var columns = connection.getMetaData().getColumns(null, "public", tableName, columnName)) {
            return columns.next() ? 1 : 0;
        }
    }

    private int countAllRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countLegacyRow(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName
                        + " WHERE pan_id = 'legacy-pan' AND request_hash IS NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName
                        + " WHERE pan_id = 'legacy-pan'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countRequestRows(Connection connection, String tableName, String requestHash) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + tableName + " WHERE pan_id = 'legacy-pan' AND request_hash = ?"
        )) {
            statement.setString(1, requestHash);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private String constraintDefinition(Connection connection, String constraintName) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?
                """)) {
            statement.setString(1, constraintName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private void createDatabase(String databaseName) throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
    }

    private void dropDatabase(String databaseName) throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(primaryTestDatabaseUrl("postgres"), "toadzip_test", "toadzip_test");
    }

    private ConfigurableEnvironment createIsolatedEnvironment(String jdbcUrl) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "isolatedTestProperties",
                Map.of(
                        "spring.datasource.url", jdbcUrl,
                        "spring.datasource.username", "toadzip_test",
                        "spring.datasource.password", "toadzip_test",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver",
                        "app.datasource.shared.url", sharedTestDatabaseUrl(),
                        "app.datasource.shared.username", "toadzip_shared_test",
                        "app.datasource.shared.password", "toadzip_shared_test",
                        "app.datasource.shared.driver-class-name", "org.postgresql.Driver",
                        "spring.main.web-application-type", "none"
                )
        ));
        environment.setActiveProfiles("local");
        return environment;
    }

    private String sharedTestDatabaseUrl() {
        return "jdbc:postgresql://127.0.0.1:" + testPort("TEST_SHARED_POSTGRES_PORT", "55433")
                + "/toadzip_shared_test";
    }

    private String primaryTestDatabaseUrl(String databaseName) {
        return "jdbc:postgresql://127.0.0.1:" + testPort("TEST_POSTGRES_PORT", "55432") + "/" + databaseName;
    }

    private String testPort(String environmentVariable, String defaultPort) {
        return System.getenv().getOrDefault(environmentVariable, defaultPort);
    }
}
