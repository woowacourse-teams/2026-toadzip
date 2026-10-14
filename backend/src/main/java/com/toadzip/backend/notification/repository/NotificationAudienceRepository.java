package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot.ComplexTarget;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationAudienceRepository {
    private static final int MAX_COMPLEXES = 10_000;
    private final JdbcTemplate jdbc;
    private final RegionCodeResolver regions;

    public Optional<String> visibleTitle(long announcementId) {
        return jdbc.query("""
                SELECT a.name FROM announcements a
                WHERE a.id=? AND NOT a.admin_deleted AND a.status IN ('ORIGINAL','원공고')
                    AND NOT EXISTS (SELECT 1 FROM announcements successor
                        WHERE successor.previous_announcement_id=a.id AND NOT successor.admin_deleted)
                """, (row, number) -> row.getString(1), announcementId).stream().findFirst();
    }

    public NotificationAudienceSnapshot snapshot(long announcementId, String title) {
        List<ComplexTarget> complexes = jdbc.query("""
                SELECT DISTINCT c.id,c.province_code,c.city_county_district_code,c.legal_dong_code
                FROM supply_rows s JOIN housing_complexes c ON c.id=s.housing_complex_id
                WHERE s.announcement_id=? AND NOT c.admin_deleted ORDER BY c.id LIMIT ?
                """, (row, number) -> new ComplexTarget(row.getLong("id"), row.getString("province_code"),
                row.getString("city_county_district_code"), row.getString("legal_dong_code")),
                announcementId, MAX_COMPLEXES + 1);
        if (complexes.size() > MAX_COMPLEXES) {
            throw new IllegalStateException("알림 공고의 단지 스냅샷 한도를 초과했습니다.");
        }
        var snapshot = new NotificationAudienceSnapshot(title, complexes, List.of());
        List<String> matchingRegions = new ArrayList<>();
        String after = "";
        while (true) {
            List<String> candidates = jdbc.queryForList("""
                    SELECT DISTINCT target_id FROM notification_subscriptions
                    WHERE target_type='REGION' AND target_id>? AND active ORDER BY target_id LIMIT 200
                    """, String.class, after);
            candidates.stream().filter(code -> regions.filterCodes(code).map(snapshot::matchesRegion).orElse(false))
                    .forEach(matchingRegions::add);
            if (candidates.size() < 200) {
                return new NotificationAudienceSnapshot(title, complexes, matchingRegions);
            }
            after = candidates.getLast();
        }
    }

    public long upperUserId() {
        return jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM users", Long.class);
    }

    public List<Long> findRecipientIds(NotificationAudienceSnapshot snapshot, Instant occurredAt,
            long afterUserId, long upperUserId, int limit, Instant now) {
        List<String> complexIds = snapshot.complexes().stream().map(value -> Long.toString(value.id())).toList();
        if (complexIds.isEmpty()) {
            return List.of();
        }
        List<String> regionIds = snapshot.regions();
        if (regionIds.isEmpty()) {
            regionIds = List.of("");
        }
        return new NamedParameterJdbcTemplate(jdbc).queryForList("""
                SELECT DISTINCT s.user_id FROM notification_subscriptions s
                WHERE s.active AND s.expires_at>:now AND s.activated_at<=:occurred
                    AND s.user_id>:after AND s.user_id<=:upper
                    AND ((s.target_type='COMPLEX' AND s.target_id IN (:complexes))
                        OR (s.target_type='REGION' AND s.target_id IN (:regions)))
                ORDER BY s.user_id LIMIT :limit
                """, Map.of("now", Timestamp.from(now), "occurred", Timestamp.from(occurredAt),
                "after", afterUserId, "upper", upperUserId, "complexes", complexIds, "regions", regionIds,
                "limit", limit), Long.class);
    }

    public boolean isSubscribed(long userId, NotificationAudienceSnapshot snapshot, Instant occurredAt, Instant now) {
        return !findRecipientIds(snapshot, occurredAt, userId - 1, userId, 1, now).isEmpty();
    }
}
