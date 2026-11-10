package com.toadzip.backend.seo.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SeoPageQueryRepository {

    private static final String PUBLIC_COMPLEXES = """
            SELECT id, GREATEST(created_at, admin_updated_at) AS last_modified
            FROM housing_complexes
            WHERE admin_deleted = false
            ORDER BY id
            """;

    // 공고 목록과 같은 공개 기준: 확정 공개, 원공고 또는 이전 공고가 있는 정정공고, 공개된 후속 공고 없음.
    private static final String LISTED_ANNOUNCEMENT_CONDITION = """
            announcement.admin_deleted = false AND announcement.release_status = 'PUBLISHED'
              AND (announcement.status IN ('ORIGINAL', '원공고')
                OR (announcement.status IN ('CORRECTION', '정정공고') AND announcement.previous_announcement_id IS NOT NULL))
              AND NOT EXISTS (
                SELECT 1
                FROM announcements successor
                WHERE successor.previous_announcement_id = announcement.id AND successor.admin_deleted = false
                  AND successor.release_status = 'PUBLISHED'
              )
            """;

    private static final String LISTED_ANNOUNCEMENTS = """
            SELECT announcement.id, GREATEST(announcement.created_at, announcement.admin_updated_at) AS last_modified
            FROM announcements announcement
            WHERE %s
            ORDER BY announcement.id
            """.formatted(LISTED_ANNOUNCEMENT_CONDITION);

    private static final String IS_LISTED_ANNOUNCEMENT = """
            SELECT EXISTS (SELECT 1 FROM announcements announcement WHERE announcement.id = :announcementId AND %s)
            """.formatted(LISTED_ANNOUNCEMENT_CONDITION);

    private static final String REGION_COUNTS = """
            SELECT province_code, city_county_district_code, supply_type, COUNT(*) AS complex_count
            FROM housing_complexes
            WHERE admin_deleted = false AND city_county_district_code IS NOT NULL
            GROUP BY province_code, city_county_district_code, supply_type
            """;

    private final JdbcClient jdbcClient;

    public SeoPageQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SeoSitemapEntry> findPublicComplexes() {
        return jdbcClient.sql(PUBLIC_COMPLEXES)
                .query((resultSet, rowNumber) -> new SeoSitemapEntry(
                        resultSet.getLong("id"), resultSet.getObject("last_modified", OffsetDateTime.class)))
                .list();
    }

    public List<SeoSitemapEntry> findListedAnnouncements() {
        return jdbcClient.sql(LISTED_ANNOUNCEMENTS)
                .query((resultSet, rowNumber) -> new SeoSitemapEntry(
                        resultSet.getLong("id"), resultSet.getObject("last_modified", OffsetDateTime.class)))
                .list();
    }

    public boolean isListedAnnouncement(long announcementId) {
        return jdbcClient.sql(IS_LISTED_ANNOUNCEMENT)
                .param("announcementId", announcementId)
                .query(Boolean.class)
                .single();
    }

    public List<SeoRegionCount> findRegionCounts() {
        return jdbcClient.sql(REGION_COUNTS)
                .query((resultSet, rowNumber) -> new SeoRegionCount(
                        resultSet.getString("province_code"),
                        resultSet.getString("city_county_district_code"),
                        resultSet.getString("supply_type"),
                        resultSet.getLong("complex_count")))
                .list();
    }
}
