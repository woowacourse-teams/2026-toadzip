package com.toadzip.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot.ComplexTarget;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 새 공고 알림의 단지 대상은 공고↔단지 연결(공고 단지)로 정한다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationAudienceLinkIntegrationTest {
    @Autowired private NotificationAudienceRepository audiences;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;

    @Test
    void 공급행이_없는_공고도_단지에_이어진_공고_단지의_단지를_알림_대상으로_삼는다() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        Announcement announcement = fixtures.announcement();
        HousingComplex complex = fixtures.complex("11140", "1114010100");
        AnnouncementComplex link = AnnouncementComplex.observe(announcement, "공고문 단지", null, null);
        link.linkByAdmin(complex, "admin", Instant.parse("2026-10-01T00:00:00Z"));
        entities.persist(link);
        entities.flush();

        assertThat(audiences.snapshot(announcement.getId(), "제목").complexes()).extracting(ComplexTarget::id)
                .containsExactly(complex.getId());
    }

    @Test
    void 관리자가_한_단지의_공급행을_모두_제외하면_그_단지는_알림_대상에서_빠진다() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        Announcement announcement = fixtures.announcement();
        HousingComplex kept = fixtures.complex("11140", "1114010100");
        HousingComplex excluded = fixtures.complex("11140", "1114010200");
        fixtures.link(announcement, kept);
        fixtures.link(announcement, excluded);
        jdbc.update("UPDATE supply_rows r SET admin_excluded = true FROM announcement_complexes ac"
                + " WHERE ac.id = r.announcement_complex_id AND ac.housing_complex_id = ?", excluded.getId());

        assertThat(audiences.snapshot(announcement.getId(), "제목").complexes()).extracting(ComplexTarget::id)
                .containsExactly(kept.getId());
    }
}
