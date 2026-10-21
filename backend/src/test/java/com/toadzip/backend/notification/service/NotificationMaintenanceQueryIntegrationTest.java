package com.toadzip.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.notification.repository.NotificationMaintenanceRepository;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationMaintenanceQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired
    private DataSource dataSource;

    private Connection connection;
    private String schema;
    private RecordingJdbcTemplate jdbc;
    private NotificationMaintenanceRepository repository;

    @BeforeEach
    void prepare() throws Exception {
        schema = "maintenance_query_" + UUID.randomUUID().toString().replace("-", "");
        connection = dataSource.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            statement.execute("""
                    CREATE TABLE notification_outbox_events (
                        id bigint PRIMARY KEY, status text, occurred_at timestamptz, target_snapshot text);
                    CREATE TABLE notification_deliveries (
                        id bigint PRIMARY KEY, status text, next_attempt_at timestamptz);
                    CREATE TABLE user_notifications (id bigint PRIMARY KEY, event_id bigint);
                    INSERT INTO notification_outbox_events
                    SELECT id, 'COMPLETED', '2026-01-01'::timestamptz, NULL FROM generate_series(1, 20000) id;
                    INSERT INTO notification_deliveries
                    SELECT id, 'SENT', '2026-01-01'::timestamptz FROM generate_series(1, 20000) id;
                    INSERT INTO user_notifications SELECT id, id FROM generate_series(1, 20000) id;
                    """);
        }
        jdbc = new RecordingJdbcTemplate(new SingleConnectionDataSource(connection, true));
        repository = new NotificationMaintenanceRepository(jdbc);
        jdbc.update("""
                INSERT INTO notification_outbox_events VALUES
                (20001, 'PENDING', ?::timestamptz - interval '90 seconds', '{}'),
                (20002, 'FAILED', ?::timestamptz - interval '100 days', '{}'),
                (20003, 'SKIPPED', ?::timestamptz - interval '100 days', NULL)
                """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO notification_deliveries VALUES
                (20001, 'PENDING', ?::timestamptz - interval '30 seconds'),
                (20002, 'RETRY', ?::timestamptz - interval '60 seconds'),
                (20003, 'SENDING', ?::timestamptz - interval '120 seconds'),
                (20004, 'FAILED', ?::timestamptz - interval '300 seconds'),
                (20005, 'SKIPPED', ?::timestamptz - interval '100 days')
                """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW));
        connection.setAutoCommit(false);
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V20261021_01__notification_maintenance_indexes.sql"));
        connection.commit();
        connection.setAutoCommit(true);
        jdbc.execute("ANALYZE notification_outbox_events; ANALYZE notification_deliveries; ANALYZE user_notifications");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection == null) {
            return;
        }
        try (Connection resource = connection; var statement = resource.createStatement()) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            connection.setSchema("public");
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void 완료_이력을_읽지_않고_진행_실패_건수와_가장_오래된_대기를_집계한다() {
        assertThat(repository.statistics(NOW)).containsExactly(
                new NotificationMaintenanceRepository.QueueStatistics("outbox", 1, 1, 90),
                new NotificationMaintenanceRepository.QueueStatistics("delivery", 3, 1, 120));
        assertThat(jdbc.plan()).contains("idx_notification_outbox_statistics",
                "idx_notification_deliveries_statistics").doesNotContain("Seq Scan");

        jdbc.execute("DELETE FROM notification_outbox_events WHERE status IN ('PENDING','FAILED')");
        jdbc.execute("DELETE FROM notification_deliveries WHERE status IN ('PENDING','RETRY','SENDING','FAILED')");
        assertThat(repository.statistics(NOW)).containsExactly(
                new NotificationMaintenanceRepository.QueueStatistics("outbox", 0, 0, 0),
                new NotificationMaintenanceRepository.QueueStatistics("delivery", 0, 0, 0));
    }

    @Test
    void 보관_기한이_지난_스냅샷만_찾고_알림_참조를_인덱스로_확인한다() {
        repository.trimEvents(NOW);
        assertThat(jdbc.plan()).contains("idx_notification_outbox_snapshot_expiry", "idx_user_notifications_event")
                .doesNotContain("Seq Scan");
        assertThat(jdbc.queryForObject("SELECT target_snapshot FROM notification_outbox_events WHERE id=20002",
                String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT target_snapshot FROM notification_outbox_events WHERE id=20001",
                String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class))
                .isEqualTo(20_003L);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private String sql;
        private Object[] arguments;

        private RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            this.sql = sql;
            this.arguments = arguments;
            return super.query(sql, rowMapper, arguments);
        }

        @Override
        public int update(String sql, Object... arguments) {
            this.sql = sql;
            this.arguments = arguments;
            return super.update(sql, arguments);
        }

        private String plan() {
            return String.join("\n", queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, arguments));
        }
    }
}
