package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.domain.RuleSetMetadata;
import com.toadzip.backend.eligibility.domain.RuleSetSection;
import com.toadzip.backend.eligibility.domain.RuleSetSection.Field;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Table and column identifiers come only from the closed schema enum, never from an HTTP request.
 */
@Repository
public class RuleSetContentRepository {
    private final JdbcTemplate jdbc;
    private final RuleSetJsonCodec codec;

    public RuleSetContentRepository(JdbcTemplate jdbc, RuleSetJsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public RuleSetContent read(long ruleSetId, RuleSetMetadata metadata) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("metadata", metadata);
        for (RuleSetSection section : RuleSetSection.values()) {
            String order =
                    section.fields().stream().map(Field::column).collect(Collectors.joining(", "));
            content.put(
                    section.property(),
                    jdbc.query(
                            "SELECT * FROM "
                                    + section.table()
                                    + " WHERE rule_set_id = ? ORDER BY "
                                    + order,
                            (result, index) -> row(section, result),
                            ruleSetId));
        }
        return codec.convert(content, RuleSetContent.class);
    }

    /**
     * Whole-draft replacement. Deferred intra-rule-set FKs allow references in either direction.
     */
    public void replace(long ruleSetId, RuleSetContent content) {
        for (RuleSetSection section : RuleSetSection.values()) {
            jdbc.update("DELETE FROM " + section.table() + " WHERE rule_set_id = ?", ruleSetId);
        }
        Map<String, Map<Long, Long>> identities = allocateIdentities(content);
        for (RuleSetSection section : RuleSetSection.values()) {
            insertSection(ruleSetId, section, content, identities);
        }
        // Surface deferred integrity errors inside the use case, before a success response or
        // publication.
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
    }

    private Map<String, Object> row(RuleSetSection section, ResultSet result) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : section.fields()) {
            values.put(field.name(), readValue(result, field));
        }
        return values;
    }

    private Object readValue(ResultSet result, Field field) throws SQLException {
        return switch (field.kind()) {
            case LONG -> result.getObject(field.column(), Long.class);
            case BIGDECIMAL -> result.getBigDecimal(field.column());
            case BOOLEAN -> result.getObject(field.column(), Boolean.class);
            case LOCALDATE -> result.getObject(field.column(), LocalDate.class);
            case OFFSETDATETIME -> result.getObject(field.column(), OffsetDateTime.class);
            case STRING -> result.getString(field.column());
        };
    }

    private Map<String, Map<Long, Long>> allocateIdentities(RuleSetContent content) {
        Map<String, Map<Long, Long>> identities = new HashMap<>();
        for (RuleSetSection section : RuleSetSection.values()) {
            if (!section.hasIdentity()) {
                continue;
            }
            List<?> rows = section.rows(content);
            List<Long> allocated =
                    jdbc.queryForList(
                            "SELECT nextval(pg_get_serial_sequence(?, 'id'))"
                                    + " FROM generate_series(1, ?)",
                            Long.class,
                            section.table(),
                            rows.size());
            Map<Long, Long> mapping = new HashMap<>();
            for (int index = 0; index < rows.size(); index++) {
                mapping.put((Long) section.value(rows.get(index), "id"), allocated.get(index));
            }
            identities.put(section.table(), mapping);
        }
        identities.put("announcement_complexes", identities.get("eligibility_rule_set_complexes"));
        return identities;
    }

    private void insertSection(
            long ruleSetId,
            RuleSetSection section,
            RuleSetContent content,
            Map<String, Map<Long, Long>> identities) {
        List<Object[]> rows = new ArrayList<>();
        for (Object row : section.rows(content)) {
            List<Object> values = new ArrayList<>();
            values.add(ruleSetId);
            for (Field field : section.fields()) {
                values.add(remap(section, row, field, identities));
            }
            rows.add(values.toArray());
        }
        if (rows.isEmpty()) {
            return;
        }
        String columns =
                section.fields().stream().map(Field::column).collect(Collectors.joining(", "));
        String parameters =
                section.fields().stream().map(field -> "?").collect(Collectors.joining(", "));
        int[] types = new int[section.fields().size() + 1];
        types[0] = Types.BIGINT;
        for (int index = 0; index < section.fields().size(); index++) {
            types[index + 1] = sqlType(section.fields().get(index));
        }
        jdbc.batchUpdate(
                "INSERT INTO "
                        + section.table()
                        + " (rule_set_id, "
                        + columns
                        + ") VALUES (?, "
                        + parameters
                        + ")",
                rows,
                types);
    }

    private Object remap(
            RuleSetSection section,
            Object row,
            Field field,
            Map<String, Map<Long, Long>> identities) {
        Object value = section.value(row, field.name());
        if (value == null) {
            return null;
        }
        if (field.name().equals("id")) {
            return identities.get(section.table()).get(value);
        }
        if (identities.containsKey(field.reference())) {
            return identities.get(field.reference()).get(value);
        }
        return value;
    }

    private int sqlType(Field field) {
        return switch (field.kind()) {
            case LONG -> Types.BIGINT;
            case BIGDECIMAL -> Types.NUMERIC;
            case BOOLEAN -> Types.BOOLEAN;
            case LOCALDATE -> Types.DATE;
            case OFFSETDATETIME -> Types.TIMESTAMP_WITH_TIMEZONE;
            case STRING -> Types.VARCHAR;
        };
    }
}
