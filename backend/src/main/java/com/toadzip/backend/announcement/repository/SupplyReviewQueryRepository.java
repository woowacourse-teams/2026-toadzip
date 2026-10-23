package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.dto.correction.SupplyReview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class SupplyReviewQueryRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public String canonicalEvidence(String evidence) {
        return jdbc.sql("""
                SELECT COALESCE(jsonb_agg(item || jsonb_build_object('rows', (
                  SELECT jsonb_agg(value ORDER BY value::text) FROM jsonb_array_elements(item -> 'rows') value
                )) ORDER BY ord), '[]'::jsonb)::text
                FROM jsonb_array_elements(CAST(:evidence AS jsonb)) WITH ORDINALITY items(item, ord)
                """).param("evidence", evidence).query(String.class).single();
    }

    public Map<String, JsonNode> values(long id) {
        var result = new LinkedHashMap<String, JsonNode>();
        jdbc.sql("SELECT target, values::text FROM (" + SupplyReviewSql.TARGET_VALUES + ") current WHERE announcement_id = :id")
                .param("id", id).query((row, index) -> Map.entry(row.getString(1), json.readTree(row.getString(2))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private JsonNode readNullable(String value) {
        if (value == null) { return json.readTree("null"); }
        return json.readTree(value);
    }

    public List<SupplyReview.Item> records(long id) {
        return jdbc.sql("SELECT * FROM (" + SupplyReviewSql.EVALUATED + ") reviews WHERE announcement_id = :id ORDER BY id DESC LIMIT 100")
                .param("id", id).query((row, index) -> new SupplyReview.Item(row.getLong("id"), row.getString("target"),
                        row.getString("outcome"), row.getString("status"), row.getBoolean("latest"),
                        json.readTree(row.getString("checked_values")), readNullable(row.getString("current_values")),
                        json.readTree(row.getString("evidence")), json.readTree(row.getString("current_evidence")),
                        row.getString("note"), row.getString("source_url"), row.getString("source_page"),
                        row.getString("actor"), row.getTimestamp("reviewed_at").toInstant())).list();
    }
}
