package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceRows;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SupplySourceQueryRepository {
    public static final String MISSING = """
            r.admin_modified AND r.myhome_source_row_identifier IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM (
            """ + IngestSourceRows.query(IngestSourceCategory.MYHOME_ANNOUNCEMENT) + """
            ) s WHERE s.source_key = r.myhome_source_row_identifier
              AND COALESCE((s.raw_payload::jsonb ->> 'active')::boolean, true))
            """;
    private final JdbcClient jdbc;

    public List<String> missingWarnings(long announcementId) {
        return jdbc.sql("SELECT id, source_housing_type_name FROM supply_rows r WHERE announcement_id = :id AND "
                        + MISSING + " ORDER BY display_order, id")
                .param("id", announcementId)
                .query((row, index) -> "공급행 #" + row.getLong(1) + " · " + row.getString(2)
                        + ": 연결된 마이홈 원천이 없거나 비활성입니다. 보정값은 유지되며 공식 자료 재확인이 필요합니다.")
                .list();
    }
}
