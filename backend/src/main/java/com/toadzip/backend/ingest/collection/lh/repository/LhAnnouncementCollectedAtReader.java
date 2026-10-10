package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LhAnnouncementCollectedAtReader {

    private final JdbcClient jdbc;

    /** 시각이 불명확한 원천은 결과에서 제외한다. 실행 완료 시각으로 대체하지 않는다. */
    public Map<String, Instant> find(ExternalDataSource source, Collection<String> requestHashes) {
        if (requestHashes.isEmpty()) {
            return Map.of();
        }
        requireSupportedSource(source);
        String sql = """
                SELECT request_hash, collected_at FROM lh_announcement_query_sources
                WHERE source = :source AND request_hash IN (:hashes)
                """;
        Map<String, Instant> result = new HashMap<>();
        jdbc.sql(sql).param("source", source.name()).param("hashes", requestHashes).query((row, index) -> {
            var collectedAt = row.getTimestamp("collected_at");
            if (collectedAt != null) {
                result.put(row.getString("request_hash"), collectedAt.toInstant());
            }
            return row.getString("request_hash");
        }).list();
        return Map.copyOf(result);
    }

    private void requireSupportedSource(ExternalDataSource source) {
        if (source != ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY
                && source != ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            throw new IllegalArgumentException("LH 공급·상세의 실제 수집 시각만 조회할 수 있습니다.");
        }
    }
}
