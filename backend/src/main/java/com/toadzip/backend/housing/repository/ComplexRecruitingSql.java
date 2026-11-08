package com.toadzip.backend.housing.repository;

import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;

public final class ComplexRecruitingSql {
    private ComplexRecruitingSql() { }

    public static String predicate(String alias) {
        return """
            EXISTS (SELECT 1 FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
                WHERE r.housing_complex_id = %s.id AND r.admin_excluded = false
                  AND a.admin_deleted = false AND a.release_status = 'PUBLISHED'
                  AND NOT EXISTS (SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = a.id AND successor.admin_deleted = false
                      AND successor.release_status = 'PUBLISHED')
                  AND %s IN ('BEFORE_APPLICATION', 'APPLYING'))
            """.formatted(alias, ApplicationScheduleSql.status("a", alias + ".id"));
    }
}
