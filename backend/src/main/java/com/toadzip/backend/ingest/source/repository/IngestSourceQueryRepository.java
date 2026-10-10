package com.toadzip.backend.ingest.source.repository;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse.Item;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Repository
public class IngestSourceQueryRepository {

    private static final BigInteger MAX_SAFE_INTEGER = new BigInteger("9007199254740991");

    private final JdbcClient jdbc;
    private final ObjectReader rawReader;

    public IngestSourceQueryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rawReader = json.readerFor(json.getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, Object.class))
                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public IngestSourcePageResponse findSources(
            IngestSourceCategory category, int page, int size, String keyword, String sourceUrl
    ) {
        SourceQuery source = new SourceQuery("(" + IngestSourceRows.query(category) + ")",
                "source.source_key", "source.name", "source.original_url", "source.updated_at", "source.raw_payload");
        String from = " FROM " + source.table() + " source";
        String filter = filter(source, keyword);
        long total = statement("SELECT COUNT(*)" + from + filter, keyword).query(Long.class).single();
        String select = """
                SELECT source.id, %s AS source_key_value, COALESCE(%s, %s) AS name_value,
                       %s AS original_url_value, source.collected_at, %s AS updated_at_value, %s AS raw_value
                """.formatted(source.key(), source.name(), source.key(), source.originalUrl(),
                        source.updatedAt(), source.raw());
        List<Item> items = statement(select + from + filter
                        + " ORDER BY source.collected_at DESC NULLS LAST, source.id DESC, source.source_key ASC"
                        + " LIMIT :size OFFSET :offset",
                        keyword)
                .param("size", size).param("offset", (long) page * size)
                .query((row, index) -> item(row, sourceUrl)).list();
        long totalPages = (total + size - 1) / size;
        return new IngestSourcePageResponse(items, page, total, totalPages, (long) page + 1 < totalPages);
    }

    private JdbcClient.StatementSpec statement(String sql, String keyword) {
        if (keyword.isEmpty()) {
            return jdbc.sql(sql);
        }
        String escaped = keyword.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
        return jdbc.sql(sql).param("keyword", "%" + escaped + "%");
    }

    private String filter(SourceQuery source, String keyword) {
        if (keyword.isEmpty()) {
            return "";
        }
        return " WHERE LOWER(COALESCE(" + source.name() + ", '')) LIKE :keyword ESCAPE '\\'"
                + " OR LOWER(" + source.key() + ") LIKE :keyword ESCAPE '\\'";
    }

    private Item item(ResultSet row, String sourceUrl) throws SQLException {
        return new Item(row.getLong("id"), row.getString("source_key_value"), row.getString("name_value"),
                sourceUrl, row.getString("original_url_value"), instant(row, "collected_at"),
                instant(row, "updated_at_value"), rawFields(row.getString("raw_value")));
    }

    private Map<String, Object> rawFields(String payload) {
        Map<String, Object> fields = rawReader.readValue(payload);
        fields.replaceAll((key, value) -> preciseValue(value));
        return fields;
    }

    private Object preciseValue(Object value) {
        if (value instanceof Map<?, ?> fields) {
            Map<String, Object> preserved = new LinkedHashMap<>();
            fields.forEach((key, field) -> preserved.put(key.toString(), preciseValue(field)));
            return preserved;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(this::preciseValue).toList();
        }
        if (value instanceof BigInteger integer && integer.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
            return integer.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value;
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private record SourceQuery(
            String table, String key, String name, String originalUrl, String updatedAt, String raw
    ) {
    }
}
