package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.NotificationContent;
import com.toadzip.backend.notification.domain.NotificationRecipient;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot.ComplexTarget;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.toadzip.backend.region.repository.RegionSearchResult;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationAudienceRepository {
    private static final int MAX_COMPLEXES = 10_000;
    private final JdbcTemplate jdbc;
    private final RegionCodeResolver regions;
    private final Map<String, String> regionNames;
    private final NotificationScheduleRepository schedules;

    public NotificationAudienceRepository(JdbcTemplate jdbc, RegionCodeResolver regions,
            RegionSearchRepository regionNames, NotificationScheduleRepository schedules) {
        this.jdbc = jdbc;
        this.regions = regions;
        this.schedules = schedules;
        this.regionNames = regionNames.findByKeyword("").stream().collect(Collectors.toUnmodifiableMap(
                RegionSearchResult::regionCode, region -> region.displayName().replace(" 전체", "")));
    }

    public Optional<String> visibleTitle(long announcementId) {
        return visibleTitle(announcementId, null);
    }

    public Optional<String> visibleTitle(long announcementId, NotificationAudienceSnapshot snapshot) {
        String types = "('ORIGINAL','원공고')";
        if (snapshot != null && snapshot.schedule() != null) {
            types = "('ORIGINAL','원공고','CORRECTION','정정공고')";
        }
        return jdbc.query("""
                SELECT a.name FROM announcements a
                WHERE a.id=? AND NOT a.admin_deleted AND a.release_status='PUBLISHED' AND a.status IN %s
                    AND NOT EXISTS (SELECT 1 FROM announcements successor
                        WHERE successor.previous_announcement_id=a.id AND NOT successor.admin_deleted
                            AND successor.release_status='PUBLISHED')
                """.formatted(types), (row, number) -> row.getString(1), announcementId).stream().findFirst();
    }

    public NotificationAudienceSnapshot snapshot(long announcementId, String title) {
        List<ComplexTarget> complexes = jdbc.query("""
                SELECT DISTINCT c.id,c.province_code,c.city_county_district_code,c.legal_dong_code
                FROM supply_rows s JOIN housing_complexes c ON c.id=s.housing_complex_id
                WHERE s.admin_excluded = false AND s.announcement_id=? AND NOT c.admin_deleted ORDER BY c.id LIMIT ?
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

    private List<Long> findScheduleRecipientIds(NotificationAudienceSnapshot snapshot, Instant occurredAt,
            long afterUserId, long upperUserId, int limit, Instant now) {
        return jdbc.queryForList("""
                SELECT user_id FROM notification_subscriptions WHERE active AND expires_at>? AND activated_at<=?
                    AND target_type='ANNOUNCEMENT' AND target_id=? AND user_id>? AND user_id<=?
                ORDER BY user_id LIMIT ?
                """, Long.class, Timestamp.from(now), Timestamp.from(occurredAt),
                Long.toString(snapshot.schedule().announcementId()), afterUserId, upperUserId, limit);
    }

    public boolean isSubscribed(long userId, NotificationAudienceSnapshot snapshot, Instant occurredAt, Instant now) {
        if (snapshot.schedule() != null) {
            return schedules.isCurrent(snapshot.schedule(), now)
                    && !findScheduleRecipientIds(snapshot, occurredAt, userId - 1, userId, 1, now).isEmpty();
        }
        String[] complexIds = snapshot.complexes().stream().map(value -> Long.toString(value.id())).toArray(String[]::new);
        if (complexIds.length == 0) {
            return false;
        }
        // 발송마다 실행되므로 대상 목록을 배열 파라미터로 넘겨 SQL 문장을 고정한다(긴 IN 목록의 계획 수립 반복 방지).
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification_subscriptions WHERE user_id=? AND active
                    AND expires_at>? AND activated_at<=?
                    AND ((target_type='COMPLEX' AND target_id=ANY(?::varchar[]))
                        OR (target_type='REGION' AND target_id=ANY(?::varchar[]))))
                """, Boolean.class, userId, Timestamp.from(now), Timestamp.from(occurredAt), complexIds,
                snapshot.regions().toArray(String[]::new)));
    }
    /** 수신자 페이지와 회원별 대표 대상(단지 우선, 더 구체적인 지역 우선)을 한 번에 조회한다. */
    public List<NotificationRecipient> findRecipients(NotificationAudienceSnapshot snapshot, Instant occurredAt,
            long afterUserId, long upperUserId, int limit, Instant now) {
        if (snapshot.schedule() != null) {
            var content = NotificationContent.reminder(snapshot.schedule());
            return findScheduleRecipientIds(snapshot, occurredAt, afterUserId, upperUserId, limit, now).stream()
                    .map(userId -> new NotificationRecipient(userId, content)).toList();
        }
        List<String> complexIds = snapshot.complexes().stream().map(value -> Long.toString(value.id())).toList();
        if (complexIds.isEmpty()) {
            return List.of();
        }
        var parameters = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now)).addValue("occurred", Timestamp.from(occurredAt))
                .addValue("after", afterUserId).addValue("upper", upperUserId).addValue("limit", limit)
                .addValue("complexes", complexIds).addValue("regions", nonEmptyTargets(snapshot.regions()));
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT DISTINCT ON (s.user_id) s.user_id,s.target_type,s.target_id FROM notification_subscriptions s
                WHERE s.active AND s.expires_at>:now AND s.activated_at<=:occurred
                    AND s.user_id>:after AND s.user_id<=:upper
                    AND ((s.target_type='COMPLEX' AND s.target_id IN (:complexes))
                        OR (s.target_type='REGION' AND s.target_id IN (:regions)))
                ORDER BY s.user_id,CASE WHEN s.target_type='COMPLEX' THEN 0 ELSE 1 END,length(s.target_id) DESC,s.target_id
                LIMIT :limit
                """, parameters, (row, index) -> new NotificationRecipient(row.getLong(1),
                        announcementContent(row.getString(2), row.getString(3))));
    }

    private NotificationContent announcementContent(String targetType, String targetId) {
        String name = "관심 단지";
        if (targetType.equals("REGION")) {
            name = regionName(targetId);
        }
        return NotificationContent.announcement(targetType, targetId, name);
    }

    public boolean isSubscribed(long userId, NotificationAudienceSnapshot snapshot, NotificationContent content, Instant occurredAt, Instant now) {
        if (!isSubscribed(userId, snapshot, occurredAt, now)) {
            return false;
        }
        if (content == null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification_subscriptions WHERE user_id=? AND target_type=? AND target_id=?
                    AND active AND expires_at>? AND activated_at<=?)
                """, Boolean.class, userId, content.targetType(), content.targetId(), Timestamp.from(now), Timestamp.from(occurredAt)));
    }

    private String regionName(String code) {
        if (regionNames.containsKey(code)) {
            return regionNames.get(code);
        }
        if (code.length() == 5) {
            return regions.resolve(code.substring(0, 2), code).orElse("관심 지역");
        }
        return regions.equivalentProvinceCodes(code).stream().flatMap(java.util.Set::stream)
                .filter(regionNames::containsKey).map(regionNames::get).findFirst().orElse("관심 지역");
    }

    private List<String> nonEmptyTargets(List<String> targets) {
        if (targets.isEmpty()) {
            return List.of("");
        }
        return targets;
    }

}
