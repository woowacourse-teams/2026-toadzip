package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

/** 정제 테스트용 현행 원천 행 시드. 수집 검증 규칙은 실제 Storage integration test에서 검증한다. */
@Component
@Profile("test")
@RequiredArgsConstructor
public class CollectedSourceRows {
    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final EntityManager entities;

    @jakarta.annotation.PostConstruct
    void prepareControlTables() {
        // create-drop는 Entity 테이블만 생성하므로 JDBC 전용 제어 테이블을 테스트에서 보충한다.
        jdbc.sql("CREATE TABLE IF NOT EXISTS myhome_announcement_lifecycle_runs "
                + "(execution_id UUID PRIMARY KEY, completed_at TIMESTAMPTZ NOT NULL)").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS lh_announcement_catalog_write_lock "
                + "(id BIGINT PRIMARY KEY CHECK (id = 1))").update();
        jdbc.sql("INSERT INTO lh_announcement_catalog_write_lock VALUES (1) ON CONFLICT DO NOTHING").update();
    }

    public <T> T save(String table, T source) {
        entities.flush();
        var values = values(source);
        Object id = values.remove("id");
        boolean exists = id != null && jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = ?")
                .param(id).query(Long.class).single() > 0;
        values.remove("source_key");
        if (table.equals("lh_announcement_catalog_entries")) {
            values.put("source_key", new BeanWrapperImpl(source).getPropertyValue("sourceKey"));
            values.remove("content_fingerprint");
            values.put("version", 0);
            values.put("query_start_date", "20200101");
            values.put("query_end_date", "20301231");
            values.put("last_collection_record_id", record("LH_ANNOUNCEMENT_CATALOG"));
        }
        if (!table.equals("lh_announcement_catalog_entries")) {
            long parent = parent(table, values);
            values.put("source_id", parent);
            if (!exists) {
                values.put("source_order", jdbc.sql("SELECT COALESCE(MAX(source_order), -1) + 1 FROM "
                        + table + " WHERE source_id = ?").param(parent).query(Integer.class).single());
            }
        }
        if (exists) {
            String assignments = String.join(", ", values.keySet().stream().map(key -> key + " = :" + key).toList());
            values.put("id", id);
            namedJdbc.update("UPDATE " + table + " SET " + assignments + " WHERE id = :id", values);
        }
        if (!exists) {
            id = new SimpleJdbcInsert(namedJdbc.getJdbcTemplate()).withTableName(table)
                    .usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id")
                    .executeAndReturnKey(values).longValue();
            ReflectionTestUtils.setField(source, "id", id);
        }
        if (values.containsKey("source_order") && new BeanWrapperImpl(source).isReadableProperty("sourceOrder")) {
            ReflectionTestUtils.setField(source, "sourceOrder", values.get("source_order"));
        }
        entities.clear();
        return source;
    }

    @org.springframework.transaction.annotation.Transactional
    public void clear() {
        entities.clear();
        for (String table : List.of("myhome_announcement_source_rows", "myhome_complex_source_rows",
                "lh_lease_catalog_source_rows", "lh_announcement_supply_rows", "lh_announcement_detail_rows",
                "lh_announcement_query_parameters", "myhome_announcement_source_bundles",
                "myhome_complex_source_bundles", "myhome_complex_source_regions", "lh_lease_catalog_source_bundles",
                "lh_announcement_catalog_entries", "lh_announcement_query_sources", "lh_announcement_collection_links",
                "myhome_announcement_lifecycle_runs", "source_collection_record_parameters",
                "source_collection_records")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    private Timestamp timestamp(Instant value) {
        if (value == null) { return null; }
        return Timestamp.from(value);
    }

    public long querySource(String source, String description, Instant collectedAt) {
        Map<String, String> parameters = java.util.Arrays.stream(description.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        var existing = jdbc.sql("SELECT id FROM lh_announcement_query_sources WHERE source = ? "
                + "AND request_hash = ?").params(source, LhAnnouncementQuery.requestHashOf(description))
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        UUID record = record(source);
        long id = jdbc.sql("""
                INSERT INTO lh_announcement_query_sources(version, source, pan_id, query_hash, request_hash,
                    request_description, collected_at, verified_empty, last_collection_record_id)
                VALUES (0, ?, ?, ?, ?, ?, ?, false, ?) RETURNING id
                """).params(source, parameters.get("PAN_ID"), LhAnnouncementQuery.requestHashOf(
                        description.replaceFirst("&COLLECTION_VERSION=[0-9]+$", "")),
                        LhAnnouncementQuery.requestHashOf(description), description,
                        timestamp(collectedAt), record).query(Long.class).single();
        parameters.forEach((name, value) -> jdbc.sql("INSERT INTO lh_announcement_query_parameters "
                + "(source_id, parameter_name, parameter_value) VALUES (?, ?, ?)").params(id, name, value).update());
        return id;
    }

    private long parent(String table, Map<String, Object> values) {
        if (table.equals("myhome_complex_source_rows")) {
            Long hsmp = (Long) values.get("hsmp_sn");
            if (hsmp == null || hsmp <= 0) { hsmp = 999999999L; }
            var existing = jdbc.sql("SELECT id FROM myhome_complex_source_bundles WHERE hsmp_sn = ?")
                    .param(hsmp).query(Long.class).optional();
            if (existing.isPresent()) { return existing.orElseThrow(); }
            String province = validCode(values.get("brtc_code"), "11", 2);
            String district = validCode(values.get("signgu_code"), "680", 3);
            long region = jdbc.sql("""
                    INSERT INTO myhome_complex_source_regions(version, province_code, district_code, collected_at,
                        last_collection_record_id) VALUES (0, ?, ?, ?, ?)
                    ON CONFLICT (province_code, district_code) DO UPDATE SET province_code = EXCLUDED.province_code
                    RETURNING id
                    """).params(province, district, values.get("collected_at"), record("MYHOME_COMPLEX"))
                    .query(Long.class).single();
            return jdbc.sql("INSERT INTO myhome_complex_source_bundles(version, hsmp_sn, region_id) "
                    + "VALUES (0, ?, ?) RETURNING id").params(hsmp, region).query(Long.class).single();
        }
        if (table.equals("myhome_announcement_source_rows")) {
            UUID record = record("MYHOME_ANNOUNCEMENT");
            values.put("collection_record_id", record);
            values.put("request_supply_type_code", "01");
            String identifier = String.valueOf(values.get("pblanc_id"));
            return jdbc.sql("INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, "
                    + "last_collection_record_id) VALUES (0, ?, ?) ON CONFLICT (pblanc_id) DO UPDATE SET "
                    + "last_collection_record_id = EXCLUDED.last_collection_record_id RETURNING id")
                    .params(identifier, record).query(Long.class).single();
        }
        if (table.equals("lh_lease_catalog_source_rows")) {
            return jdbc.sql("INSERT INTO lh_lease_catalog_source_bundles(version, scope_key, collected_at, "
                    + "last_collection_record_id) VALUES (0, 'ALL', ?, ?) ON CONFLICT (scope_key) DO UPDATE SET "
                    + "scope_key = EXCLUDED.scope_key RETURNING id")
                    .params(values.get("collected_at"), record("LH_LEASE_CATALOG")).query(Long.class).single();
        }
        String source = "LH_ANNOUNCEMENT_DETAIL";
        if (table.equals("lh_announcement_supply_rows")) { source = "LH_ANNOUNCEMENT_SUPPLY"; }
        String pan = (String) values.remove("pan_id");
        String hash = (String) values.remove("request_hash");
        var query = new LhAnnouncementQuery(pan, "03", "06", "07", "062");
        String description = query.description() + "&COLLECTION_VERSION=6";
        if (hash == null) { hash = LhAnnouncementQuery.requestHashOf(description); }
        var existing = jdbc.sql("SELECT id FROM lh_announcement_query_sources WHERE source = ? "
                + "AND pan_id = ? AND request_hash = ?").params(source, pan, hash).query(Long.class).optional();
        if (existing.isPresent()) { return existing.orElseThrow(); }
        return jdbc.sql("""
                INSERT INTO lh_announcement_query_sources(version, source, pan_id, query_hash, request_hash,
                    request_description, collected_at, verified_empty, last_collection_record_id)
                VALUES (0, ?, ?, ?, ?, ?, ?, false, ?) RETURNING id
                """).params(source, pan, LhAnnouncementQuery.requestHashOf("fixture:" + hash), hash, description,
                        values.get("collected_at"), record(source)).query(Long.class).single();
    }

    public UUID record(String source) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO source_collection_records(id, version, source, started_at, finished_at, status, "
                + "stored_row_count) VALUES (?, 0, ?, now(), now(), 'SUCCESS', 1)").params(id, source).update();
        return id;
    }

    private String validCode(Object raw, String fallback, int length) {
        if (raw != null && raw.toString().matches("[0-9]{" + length + "}")) { return raw.toString(); }
        return fallback;
    }

    private Map<String, Object> values(Object source) {
        var wrapper = new BeanWrapperImpl(source);
        Map<String, Object> result = new LinkedHashMap<>();
        for (var descriptor : wrapper.getPropertyDescriptors()) {
            String name = descriptor.getName();
            if (name.equals("class")) { continue; }
            Object value = wrapper.getPropertyValue(name);
            if (value instanceof Instant instant) { value = Timestamp.from(instant); }
            result.put(name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT), value);
        }
        return result;
    }
}
