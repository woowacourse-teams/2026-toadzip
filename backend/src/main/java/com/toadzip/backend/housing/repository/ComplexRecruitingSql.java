package com.toadzip.backend.housing.repository;

import com.toadzip.backend.announcement.repository.AnnouncementComplexLinkSql;
import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;

public final class ComplexRecruitingSql {
    private ComplexRecruitingSql() { }

    public static String predicate(String alias) {
        return """
            EXISTS (SELECT 1 FROM %s link JOIN announcements a ON a.id = link.announcement_id
                WHERE link.housing_complex_id = %s.id
                  AND a.admin_deleted = false AND a.release_status = 'PUBLISHED'
                  AND NOT EXISTS (SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = a.id AND successor.admin_deleted = false
                      AND successor.release_status = 'PUBLISHED')
                  AND %s IN ('BEFORE_APPLICATION', 'APPLYING'))
            """.formatted(AnnouncementComplexLinkSql.LINKS, alias, ApplicationScheduleSql.status("a", alias + ".id"));
    }
}
