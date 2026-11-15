package com.toadzip.backend.housing.repository;

import com.toadzip.backend.announcement.repository.AnnouncementComplexLinkSql;

final class HousingComplexRepresentativeSql {

    static final String WITH_CLAUSE = """
            WITH latest_leaf AS (
                SELECT announcement.*
                FROM announcements announcement
                WHERE announcement.admin_deleted = false AND announcement.release_status = 'PUBLISHED' AND NOT EXISTS (
                    SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = announcement.id AND successor.admin_deleted = false
                      AND successor.release_status = 'PUBLISHED'
                )
                  AND announcement.status NOT IN ('CANCELLATION', '취소공고')
            ), representative AS (
                SELECT DISTINCT ON (link.housing_complex_id)
                       link.housing_complex_id,
                       announcement.id AS announcement_id,
                       announcement.id,
                       announcement.status,
                       announcement.application_schedule_reviewed,
                       announcement.status AS publication_type,
                       announcement.recruitment_type,
                       announcement.posted_date,
                       announcement.application_start_date,
                       announcement.application_end_date
                FROM %s link
                JOIN latest_leaf announcement ON announcement.id = link.announcement_id
                ORDER BY link.housing_complex_id, announcement.posted_date DESC, announcement.id DESC
            )
            """.formatted(AnnouncementComplexLinkSql.LINKS);

    static final String LEFT_JOIN = """
            LEFT JOIN representative ON representative.housing_complex_id = housing_complex.id
            """;

    private HousingComplexRepresentativeSql() {
    }
}
