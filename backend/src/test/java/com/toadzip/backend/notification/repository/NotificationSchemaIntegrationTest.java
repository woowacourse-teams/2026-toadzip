package com.toadzip.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class NotificationSchemaIntegrationTest {
    private static final String USER = "toadzip_test";
    private static final String PASSWORD = "toadzip_test";

    @Test
    void 기존구독_마이그레이션과_중복_상태_참조_제약을_검증한다() throws Exception {
        String database = "notification_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("toadzip_test")) {
            admin.createStatement().execute("CREATE DATABASE " + database);
            try {
                verifyMigration(database);
            } finally {
                admin.createStatement().execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private void verifyMigration(String database) throws Exception {
        Flyway.configure().dataSource(url(database), USER, PASSWORD).target("20261011.01").load().migrate();
        try (Connection connection = connect(database)) {
            connection.createStatement().execute("""
                    INSERT INTO users(id,login_identifier,created_at) VALUES(1,'notification-schema',now());
                    INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                    VALUES(1,'REGION','11',true,'2026-10-12T00:00:00Z','2027-10-12T00:00:00Z');
                    """);
        }
        Flyway migration = Flyway.configure().dataSource(url(database), USER, PASSWORD).load();
        migration.migrate();
        migration.validate();
        var configuration = new org.hibernate.cfg.Configuration()
                .addAnnotatedClass(com.toadzip.backend.notification.domain.NotificationOutboxEvent.class)
                .addAnnotatedClass(com.toadzip.backend.notification.domain.UserNotification.class)
                .addAnnotatedClass(com.toadzip.backend.notification.domain.NotificationPushDevice.class)
                .addAnnotatedClass(com.toadzip.backend.notification.domain.NotificationDelivery.class)
                .setProperty("hibernate.connection.url", url(database))
                .setProperty("hibernate.connection.username", USER)
                .setProperty("hibernate.connection.password", PASSWORD)
                .setProperty("hibernate.hbm2ddl.auto", "validate");
        try (var factory = configuration.buildSessionFactory()) {
            assertThat(factory.isOpen()).isTrue();
        }
        try (Connection connection = connect(database)) {
            var statement = connection.createStatement();
            var tables = statement.executeQuery("SELECT to_regclass('user_notifications')::text");
            tables.next();
            assertThat(tables.getString(1)).isEqualTo("user_notifications");
            var activation = statement.executeQuery("SELECT activated_at = updated_at FROM notification_subscriptions");
            activation.next();
            assertThat(activation.getBoolean(1)).isTrue();
            statement.execute("""
                    INSERT INTO notification_outbox_events(id,event_key,announcement_id,source_identifier,occurred_at,
                        status,next_attempt_at,attempts,recipient_cursor)
                    VALUES(1,'ANNOUNCEMENT_CREATED:test',101,'test',now(),'PENDING',now(),0,0);
                    INSERT INTO user_notifications(user_id,event_id,announcement_id,title,created_at,expires_at)
                    VALUES(1,1,101,'새 공고',now(),now()+interval '90 days');
                    """);
            assertRejected(connection, """
                    INSERT INTO user_notifications(user_id,event_id,announcement_id,title,created_at,expires_at)
                    VALUES(1,1,101,'중복',now(),now()+interval '90 days')
                    """, "23505");
            assertRejected(connection, "UPDATE notification_outbox_events SET status='UNKNOWN'", "23514");
            assertRejected(connection, "UPDATE user_notifications SET user_id=999", "23503");
            connection.setAutoCommit(false);
            statement.execute("UPDATE user_notifications SET read_at=now()");
            connection.rollback();
            var unread = statement.executeQuery("SELECT count(*) FROM user_notifications WHERE read_at IS NULL");
            unread.next();
            assertThat(unread.getLong(1)).isEqualTo(1);
        }
    }

    private void assertRejected(Connection connection, String sql, String state) {
        assertThatThrownBy(() -> connection.createStatement().execute(sql))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo(state);
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), USER, PASSWORD);
    }

    private static String url(String database) {
        return "jdbc:postgresql://127.0.0.1:" + System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432")
                + "/" + database;
    }
}
