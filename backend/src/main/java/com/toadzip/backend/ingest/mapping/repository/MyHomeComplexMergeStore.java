package com.toadzip.backend.ingest.mapping.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyHomeComplexMergeStore {

    private static final List<String> REFERENCE_TABLES = List.of(
            "housing_types", "supply_rows", "favorite_housing_complexes", "announcement_application_schedules"
    );
    private static final List<String> COMPLEX_COLUMNS = List.of(
            "id", "city_county_district_code", "latitude", "legal_dong_code", "longitude", "pnu",
            "province_code", "road_address", "completion_date", "corridor_type", "elevator_installed",
            "heating_type", "housing_type", "image_url", "name", "parking_space_count", "provider",
            "recent_one_year_move_out_count", "source_complex_identifier", "supply_type", "total_household_count",
            "version", "created_at", "admin_deleted", "admin_modified", "source_review_required", "admin_updated_at"
    );

    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final ObjectMapper json;

    public MyHomeComplexMergeStore(JdbcClient jdbc, EntityManager entityManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.json = json;
    }

    public void lockReferences(List<Long> ids) {
        for (String table : REFERENCE_TABLES) {
            jdbc.sql("SELECT id FROM " + table + " WHERE housing_complex_id IN (:ids) ORDER BY id FOR UPDATE")
                    .param("ids", ids).query(Long.class).list();
        }
        jdbc.sql("""
                SELECT source_complex_identifier FROM myhome_complex_links
                WHERE housing_complex_id IN (:ids) ORDER BY source_complex_identifier FOR UPDATE
                """).param("ids", ids).query(String.class).list();
    }

    public void lockEvidence() {
        jdbc.sql("LOCK TABLE myhome_complex_source, lh_catalog_source IN SHARE MODE").update();
    }

    public String state(List<Long> ids) {
        entityManager.flush();
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'version', 1,
                    'complexes', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id)
                        FROM housing_complexes c WHERE c.id IN (:ids)), '[]'),
                    'housing_types', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id)
                        FROM housing_types t WHERE t.housing_complex_id IN (:ids)), '[]'),
                    'supply_rows', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id)
                        FROM supply_rows s WHERE s.housing_complex_id IN (:ids)), '[]'),
                    'favorite_housing_complexes', COALESCE((SELECT jsonb_agg(to_jsonb(f) ORDER BY f.id)
                        FROM favorite_housing_complexes f WHERE f.housing_complex_id IN (:ids)), '[]'),
                    'announcement_application_schedules', COALESCE((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id)
                        FROM announcement_application_schedules a WHERE a.housing_complex_id IN (:ids)), '[]'),
                    'links', COALESCE((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.source_complex_identifier)
                        FROM myhome_complex_links l WHERE l.housing_complex_id IN (:ids)), '[]'),
                    'aliases', COALESCE((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id)
                        FROM housing_complex_aliases a
                        WHERE a.housing_complex_id IN (:ids) OR a.id IN (:ids)), '[]')
                )::text
                """).param("ids", ids).query(String.class).single();
    }

    public String evidence(List<Long> sourceIds, List<Long> lhIds) {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'myhome', (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.source_key)
                        FROM myhome_complex_source s WHERE s.id IN (:sourceIds)),
                    'lh', (SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id)
                        FROM lh_catalog_source l WHERE l.id IN (:lhIds))
                )::text
                """).param("sourceIds", sourceIds).param("lhIds", lhIds).query(String.class).single();
    }

    public boolean sameState(String left, String right) {
        return jdbc.sql("SELECT CAST(:left AS jsonb) = CAST(:right AS jsonb)")
                .param("left", left).param("right", right).query(Boolean.class).single();
    }

    public void transferAndRemove(long representativeId, List<Long> donorIds, UUID mergeId) {
        entityManager.flush();
        for (String table : REFERENCE_TABLES) {
            jdbc.sql("UPDATE " + table + " SET housing_complex_id = :target WHERE housing_complex_id IN (:donors)")
                    .param("target", representativeId).param("donors", donorIds).update();
        }
        for (long donorId : donorIds) {
            jdbc.sql("INSERT INTO housing_complex_aliases (id, housing_complex_id, merge_id) VALUES (?, ?, ?)")
                    .params(donorId, representativeId, mergeId).update();
        }
        jdbc.sql("DELETE FROM housing_complexes WHERE id IN (:ids)").param("ids", donorIds).update();
        entityManager.clear();
    }

    public void restore(String before, UUID mergeId) {
        String columns = String.join(", ", COMPLEX_COLUMNS);
        String updates = String.join(", ", COMPLEX_COLUMNS.stream()
                .filter(column -> !column.equals("id"))
                .map(column -> column + " = EXCLUDED." + column).toList());
        jdbc.sql("INSERT INTO housing_complexes (" + columns + ") SELECT " + columns
                + " FROM jsonb_populate_recordset(NULL::housing_complexes, CAST(:state AS jsonb)->'complexes')"
                + " ON CONFLICT (id) DO UPDATE SET " + updates).param("state", before).update();
        for (String table : REFERENCE_TABLES) {
            jdbc.sql("UPDATE " + table + " current SET housing_complex_id = original.housing_complex_id"
                    + " FROM jsonb_populate_recordset(NULL::" + table
                    + ", CAST(:state AS jsonb)->'" + table + "') original WHERE current.id = original.id")
                    .param("state", before).update();
        }
        jdbc.sql("""
                UPDATE myhome_complex_links current
                SET housing_complex_id = original.housing_complex_id,
                    merge_id = original.merge_id, approved_household_count = original.approved_household_count
                FROM jsonb_populate_recordset(NULL::myhome_complex_links, CAST(:state AS jsonb)->'links') original
                WHERE current.source_complex_identifier = original.source_complex_identifier
                """).param("state", before).update();
        jdbc.sql("DELETE FROM housing_complex_aliases WHERE merge_id = :id").param("id", mergeId).update();
        entityManager.clear();
    }

    public List<ComplexMergeCandidateRow> candidates(long afterId, int size) {
        return jdbc.sql("""
                SELECT min(c.id) AS representative_id, c.name, c.road_address, c.pnu, c.provider, c.supply_type,
                    jsonb_agg(jsonb_build_object('complexId', c.id, 'sourceIdentifier', c.source_complex_identifier,
                        'householdCount', c.total_household_count) ORDER BY c.id)::text AS sources
                FROM housing_complexes c
                JOIN myhome_complex_links l ON l.housing_complex_id = c.id
                WHERE l.merge_id IS NULL AND c.provider = 'LH'
                GROUP BY c.name, c.road_address, c.pnu, c.provider, c.supply_type
                HAVING count(*) BETWEEN 2 AND 20 AND min(c.id) > :afterId
                ORDER BY min(c.id) LIMIT :size
                """).param("afterId", afterId).param("size", size).query((row, number) ->
                        new ComplexMergeCandidateRow(
                                row.getLong("representative_id"), row.getString("name"), row.getString("road_address"),
                                row.getString("pnu"), row.getString("provider"), row.getString("supply_type"),
                                json.readValue(row.getString("sources"), json.getTypeFactory()
                                        .constructCollectionType(List.class, ComplexMergeCandidateRow.Source.class))
                        )).list();
    }
}
