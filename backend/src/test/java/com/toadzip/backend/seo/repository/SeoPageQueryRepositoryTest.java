package com.toadzip.backend.seo.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SeoPageQueryRepository.class)
class SeoPageQueryRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SeoPageQueryRepository repository;

    @Test
    void 사이트맵_단지는_관리자가_지우지_않은_단지만_ID_순으로_조회한다() {
        HousingComplex visible = persistComplex("공개 단지", "행복주택", "11140");
        HousingComplex deleted = persistComplex("삭제 단지", "행복주택", "11140");
        entityManager.flush();
        markDeleted("housing_complexes", deleted.getId());

        List<SeoSitemapEntry> entries = repository.findPublicComplexes();

        assertAll(
                () -> assertEquals(List.of(visible.getId()), entries.stream().map(SeoSitemapEntry::id).toList()),
                () -> assertTrue(entries.getFirst().lastModified() != null)
        );
    }

    @Test
    void 사이트맵_공고는_공고_목록과_같은_공개_기준을_따른다() {
        Announcement original = persistAnnouncement(null, "ORIGINAL", "original");
        Announcement superseded = persistAnnouncement(null, "ORIGINAL", "superseded");
        Announcement correction = persistAnnouncement(superseded, "CORRECTION", "correction");
        Announcement cancelled = persistAnnouncement(null, "ORIGINAL", "cancelled");
        Announcement cancellation = persistAnnouncement(cancelled, "CANCELLATION", "cancellation");
        Announcement deleted = persistAnnouncement(null, "ORIGINAL", "deleted");
        Announcement unconfirmed = persistAnnouncement(null, "ORIGINAL", "unconfirmed");
        entityManager.flush();
        markDeleted("announcements", deleted.getId());
        entityManager.createNativeQuery("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = :id")
                .setParameter("id", unconfirmed.getId()).executeUpdate();

        List<Long> listed = repository.findListedAnnouncements().stream().map(SeoSitemapEntry::id).toList();

        assertAll(
                () -> assertEquals(List.of(original.getId(), correction.getId()), listed),
                () -> assertTrue(repository.isListedAnnouncement(original.getId())),
                () -> assertFalse(repository.isListedAnnouncement(superseded.getId())),
                () -> assertFalse(repository.isListedAnnouncement(cancelled.getId())),
                () -> assertFalse(repository.isListedAnnouncement(cancellation.getId())),
                () -> assertFalse(repository.isListedAnnouncement(Long.MAX_VALUE))
        );
    }

    @Test
    void 지역_단지_수는_시군구와_저장된_공급유형별로_센다() {
        persistComplex("중구 행복 1", "행복주택", "11140");
        persistComplex("중구 행복 2", "HAPPY_HOUSING", "11140");
        persistComplex("중구 국민", "국민임대", "11140");
        HousingComplex deleted = persistComplex("삭제 단지", "국민임대", "11140");
        entityManager.flush();
        markDeleted("housing_complexes", deleted.getId());

        List<SeoRegionCount> counts = repository.findRegionCounts();

        assertEquals(
                List.of(new SeoRegionCount("11", "11140", "HAPPY_HOUSING", 1),
                        new SeoRegionCount("11", "11140", "국민임대", 1),
                        new SeoRegionCount("11", "11140", "행복주택", 1)),
                counts.stream().sorted(Comparator.comparing(SeoRegionCount::storedRentalType)).toList());
    }

    private void markDeleted(String table, long id) {
        entityManager.createNativeQuery("UPDATE " + table + " SET admin_deleted = true WHERE id = :id")
                .setParameter("id", id).executeUpdate();
    }

    private HousingComplex persistComplex(String name, String rentalType, String districtCode) {
        HousingComplex complex = HousingComplex.create(name, "source-" + name, rentalType,
                Address.create("서울특별시 중구 세종대로 110", "1114010100100010000", "1114010100",
                        districtCode.substring(0, 2), districtCode,
                        new BigDecimal("37.500000"), new BigDecimal("126.900000")),
                100, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, 7);
        entityManager.persist(complex);
        return complex;
    }

    private Announcement persistAnnouncement(Announcement previous, String status, String suffix) {
        String previousSourceIdentifier = null;
        if (previous != null) {
            previousSourceIdentifier = previous.getSourceAnnouncementIdentifier();
        }
        Announcement announcement = Announcement.create("source-" + suffix, previousSourceIdentifier, previous,
                "공고 " + suffix, status, "행복주택", "신규모집", "LH", TODAY, TODAY, TODAY.plusDays(2),
                TODAY.plusMonths(1), "https://example.com/announcements/" + suffix, null, 0, null, null,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)));
        entityManager.persist(announcement);
        return announcement;
    }
}
