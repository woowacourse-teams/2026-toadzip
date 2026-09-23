package com.toadzip.backend.housing.repository;

final class HousingComplexRepresentativeSql {

    static final String WITH_CLAUSE = """
            WITH latest_leaf AS (
                SELECT announcement.*
                FROM announcements announcement
                WHERE NOT EXISTS (
                    SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = announcement.id
                )
                  AND announcement.status NOT IN ('CANCELLATION', '취소공고')
            ), representative AS (
                SELECT DISTINCT ON (supply_row.housing_complex_id)
                       supply_row.housing_complex_id,
                       announcement.id AS announcement_id,
                       announcement.status AS publication_type,
                       announcement.recruitment_type,
                       announcement.posted_date,
                       announcement.application_start_date,
                       announcement.application_end_date
                FROM supply_rows supply_row
                JOIN latest_leaf announcement ON announcement.id = supply_row.announcement_id
                WHERE supply_row.housing_complex_id IS NOT NULL
                ORDER BY supply_row.housing_complex_id, announcement.posted_date DESC, announcement.id DESC
            )
            """;

    static final String LEFT_JOIN = """
            LEFT JOIN representative ON representative.housing_complex_id = housing_complex.id
            """;

    private HousingComplexRepresentativeSql() {
    }
}
