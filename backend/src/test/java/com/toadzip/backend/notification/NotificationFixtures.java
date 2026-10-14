package com.toadzip.backend.notification;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public record NotificationFixtures(EntityManager entities, JdbcTemplate jdbc) {
    public static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");

    public Announcement announcement() {
        LocalDate date = LocalDate.of(2026, 10, 13);
        var announcement = Announcement.create(UUID.randomUUID().toString(), null, null, "관심 단지 새 공고",
                AnnouncementPublicationType.ORIGINAL, RentalType.NATIONAL_RENTAL, RecruitmentType.NEW, AgencyCode.LH,
                date, date, date.plusDays(7), date.plusDays(30), "https://example.com/notice", null, 0L, null);
        entities.persist(announcement);
        entities.flush();
        return announcement;
    }

    public HousingComplex complex(String district, String legalDong) {
        var complex = HousingComplex.create("알림 테스트 단지", UUID.randomUUID().toString(), "NATIONAL_RENTAL",
                Address.create("서울특별시 중구 세종대로 110", legalDong + "100010000", legalDong,
                        district.substring(0, 2), district, new BigDecimal("37.566500"), new BigDecimal("126.978000")),
                100, "LH", LocalDate.of(2020, 6, 30), "INDIVIDUAL", "APARTMENT", "STAIR", true, 80, null, 0);
        entities.persist(complex);
        entities.flush();
        return complex;
    }

    public void link(Announcement announcement, HousingComplex complex) {
        entities.persist(SupplyRow.create(announcement, complex, null, UUID.randomUUID().toString(), 0,
                "알림 테스트 단지", "36A", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10));
        entities.flush();
    }

    public long event(Announcement announcement) {
        return jdbc.queryForObject("""
                INSERT INTO notification_outbox_events(event_key,announcement_id,source_identifier,occurred_at,
                    status,attempts,next_attempt_at,recipient_cursor)
                VALUES(?,?,?,?,'PENDING',0,?,0) RETURNING id
                """, Long.class, "ANNOUNCEMENT_CREATED:" + announcement.getSourceAnnouncementIdentifier(),
                announcement.getId(), announcement.getSourceAnnouncementIdentifier(), Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    public void user(long id) {
        jdbc.update("INSERT INTO users(id,login_identifier,created_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                id, "notification-fixture-" + id, Timestamp.from(NOW));
    }

    public void subscribe(long user, String type, String target, Instant activatedAt) {
        jdbc.update("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at,
                    activated_at) VALUES(?,?,?,true,?,?,?)
                """, user, type, target, Timestamp.from(activatedAt), Timestamp.from(NOW.plusSeconds(86400)),
                Timestamp.from(activatedAt));
    }

    public UUID device(long user) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_push_devices(id,user_id,fid,binding_version,enabled,
                    consented_at,registered_at,last_seen_at) VALUES(?,?,?,1,true,?,?,?)
                """, id, user, "fixture-" + id, Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW));
        return id;
    }
}
