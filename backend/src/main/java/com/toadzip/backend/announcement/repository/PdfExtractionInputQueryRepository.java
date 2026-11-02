package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PDF 추출 작업에 담을 입력 스냅숏을 읽는다. */
@Repository
@RequiredArgsConstructor
public class PdfExtractionInputQueryRepository {
    // 공고 번호마다 공급행이 있는 LH 공급 원천 중 가장 최근에 수집한 것 하나를 쓴다.
    private static final String LH_SUPPLY_ROWS = """
            SELECT r.source_order, r.complex_label, r.type_name, r.exclusive_area, r.supply_area,
                   r.supplied_unit_count, r.total_unit_count
            FROM lh_announcement_supply_rows r
            WHERE r.source_id = (
                SELECT s.id FROM lh_announcement_query_sources s
                JOIN announcements a ON a.lh_pan_id = s.pan_id
                WHERE a.id = :id AND s.source = 'LH_ANNOUNCEMENT_SUPPLY'
                  AND EXISTS (SELECT 1 FROM lh_announcement_supply_rows x WHERE x.source_id = s.id)
                ORDER BY s.collected_at DESC NULLS LAST, s.id DESC LIMIT 1)
            ORDER BY r.source_order, r.id
            """;

    private final JdbcClient jdbc;

    public PdfExtractionInput read(long announcementId) {
        return new PdfExtractionInput(jdbc.sql(LH_SUPPLY_ROWS).param("id", announcementId)
                .query((row, index) -> new PdfExtractionInput.LhSupplyRow(row.getInt(1), row.getString(2),
                        row.getString(3), row.getString(4), row.getString(5), row.getString(6), row.getString(7)))
                .list());
    }
}
