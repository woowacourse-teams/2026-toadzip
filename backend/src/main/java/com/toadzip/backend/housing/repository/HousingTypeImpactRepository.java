package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.dto.response.HousingTypeImpact;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class HousingTypeImpactRepository {
    private final JdbcClient jdbc;
    public List<HousingTypeImpact.AffectedSupply> rows(long typeId) {
        return jdbc.sql("""
                SELECT a.id, a.name, r.id AS row_id, r.admin_excluded, a.version
                FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
                WHERE r.housing_type_id = :typeId
                   OR EXISTS (SELECT 1 FROM supply_row_housing_types link
                              WHERE link.supply_row_id = r.id AND link.housing_type_id = :typeId)
                ORDER BY a.id, r.id
                """).param("typeId", typeId).query((row, index) -> new HousingTypeImpact.AffectedSupply(
                        row.getLong("id"), row.getString("name"), row.getLong("row_id"), row.getBoolean("admin_excluded"),
                        row.getLong("version"))).list();
    }
}
