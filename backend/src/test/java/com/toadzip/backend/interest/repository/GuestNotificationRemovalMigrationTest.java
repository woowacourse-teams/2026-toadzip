package com.toadzip.backend.interest.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class GuestNotificationRemovalMigrationTest {

    @Test
    void 비회원_자료만_제거하고_회원_설정과_기존_마이그레이션_이력을_보존한다() throws Exception {
        String database = "guest_removal_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261013.01").load().migrate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    query.execute("""
                            INSERT INTO users(id,login_identifier,email,created_at)
                            VALUES (1,'google:member','member@example.test',now());
                            INSERT INTO notification_subscriptions
                                (user_id,target_type,target_id,active,updated_at,expires_at)
                            VALUES (1,'REGION','11',true,now(),now()+interval '12 months');
                            INSERT INTO notification_guest_email_preferences(client_id,email,updated_at)
                            VALUES ('00000000-0000-4000-8000-000000000001','guest@example.test',now());
                            INSERT INTO notification_guest_subscriptions
                                (client_id,target_type,target_id,active,updated_at,expires_at)
                            VALUES ('00000000-0000-4000-8000-000000000001','REGION','11',true,
                                now(),now()+interval '12 months');
                            INSERT INTO notification_guest_cancellation_requests(id,email,requested_at,failed_attempts)
                            VALUES ('00000000-0000-4000-8000-000000000002','guest@example.test',now(),0);
                            """);
                    String before = memberSnapshot(query);
                    Flyway flyway = migration(database).load();
                    flyway.migrate();
                    flyway.validate();
                    assertEquals(before, memberSnapshot(query));
                    try (var result = query.executeQuery("""
                            SELECT to_regclass('notification_guest_subscriptions'),
                                to_regclass('notification_guest_email_preferences'),
                                to_regclass('notification_guest_cancellation_requests'),
                                to_regclass('user_notifications') IS NOT NULL
                            """)) {
                        assertTrue(result.next());
                        for (int column = 1; column <= 3; column++) {
                            assertEquals(null, result.getObject(column));
                        }
                        assertTrue(result.getBoolean(4));
                    }
                    try (var result = query.executeQuery("""
                            SELECT success FROM flyway_schema_history WHERE version='20260930.06'
                            """)) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1));
                        assertFalse(result.next());
                    }
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private String memberSnapshot(Statement query) throws Exception {
        try (var result = query.executeQuery("""
                SELECT to_jsonb(member)::text || to_jsonb(subscription)::text
                FROM users member JOIN notification_subscriptions subscription ON subscription.user_id=member.id
                """)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration migration(String database) {
        return Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration");
    }

    private Connection connect(String database) throws Exception {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        return PostgresTestDatabases.primaryUrl(database);
    }
}
