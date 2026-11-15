package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V20261116_01이 저장된 공급대상 표기를 AI 워커 정규화(target_groups.groups)와 같은 분류로 채우는지 본다. 표기와 기대 분류는 워커
 * 테스트와 함께 쓰는 계약 예시(supply-target-groups.json)다. 모르는 낱말이 섞인 표기는 분류를 정하지 않고, 상수 밖 이름은 제약이 막는다.
 */
class SupplyTargetGroupsMigrationTest {
    private static final Path CONTRACT = Path.of("../ai-worker/contract/announcement-pdf-extraction/supply-target-groups.json");

    @Test
    void 서버_분류_상수는_계약의_상수와_같은_순서다() throws Exception {
        assertThat(Arrays.stream(SupplyTargetGroup.values()).map(Enum::name).toList()).isEqualTo(contractGroups());
    }

    @Test
    void 저장된_공급대상_표기를_워커와_같은_분류로_채운다() throws Exception {
        String database = "supply_target_groups_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261115.03").load().migrate();
                Map<String, String> expected = contractLabels();
                try (Connection connection = connect(database)) {
                    seed(connection, expected.keySet().stream().toList());
                }

                Flyway flyway = migration(database).target("20261116.01").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    Map<String, String> filled = new LinkedHashMap<>();
                    try (var result = query.executeQuery(
                            "SELECT label, target_groups::text FROM announcement_supply_targets ORDER BY id")) {
                        while (result.next()) {
                            filled.put(result.getString(1), result.getString(2));
                        }
                    }
                    assertThat(filled).containsExactlyEntriesOf(expected);
                    // 제약은 계약의 상수를 모두 받는다.
                    query.execute("UPDATE announcement_supply_targets SET target_groups = '"
                            + new JsonMapper().writeValueAsString(contractGroups()) + "'::jsonb WHERE id = 1");
                    assertThatThrownBy(() -> query.execute("""
                            UPDATE announcement_supply_targets SET target_groups = '["DISABLED"]'::jsonb WHERE id = 1
                            """)).isInstanceOf(SQLException.class)
                            .hasMessageContaining("ck_announcement_supply_targets_groups");
                    assertThatThrownBy(() -> query.execute("""
                            UPDATE announcement_supply_targets SET target_groups = '"YOUTH"'::jsonb WHERE id = 1
                            """)).isInstanceOf(SQLException.class)
                            .hasMessageContaining("ck_announcement_supply_targets_groups");
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private static void seed(Connection connection, List<String> labels) throws SQLException {
        try (Statement seed = connection.createStatement()) {
            seed.execute("""
                    INSERT INTO announcements (id, name, original_url, posted_date, provider,
                        source_announcement_identifier, status, view_count, release_status)
                    VALUES (1, '공고 1', 'https://example.com/1', DATE '2026-11-01', 'LH', 'groups-1', 'ORIGINAL', 0,
                            'UNCONFIRMED')
                    """);
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO announcement_supply_targets (id, announcement_id, label, display_order) VALUES (?, 1, ?, ?)
                """)) {
            for (int index = 0; index < labels.size(); index++) {
                insert.setLong(1, index + 1);
                insert.setString(2, labels.get(index));
                insert.setInt(3, index);
                insert.executeUpdate();
            }
        }
    }

    private static List<String> contractGroups() throws Exception {
        List<String> groups = new ArrayList<>();
        contract().path("groups").forEach(group -> groups.add(group.asString()));
        return groups;
    }

    /** 계약 예시 표기 → DB가 내는 분류 JSON 글자(["NEWLYWED", "SINGLE_PARENT"]). */
    private static Map<String, String> contractLabels() throws Exception {
        Map<String, String> labels = new LinkedHashMap<>();
        for (JsonNode item : contract().path("labels")) {
            List<String> groups = new ArrayList<>();
            item.path("groups").forEach(group -> groups.add("\"" + group.asString() + "\""));
            labels.put(item.path("label").asString(), "[" + String.join(", ", groups) + "]");
        }
        return labels;
    }

    private static JsonNode contract() throws Exception {
        return new JsonMapper().readTree(Files.readString(CONTRACT));
    }

    private FluentConfiguration migration(String database) {
        return Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration");
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        return PostgresTestDatabases.primaryUrl(database);
    }
}
