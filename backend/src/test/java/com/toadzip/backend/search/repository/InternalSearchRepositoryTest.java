package com.toadzip.backend.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.search.domain.SearchMatch;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(InternalSearchRepository.class)
class InternalSearchRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InternalSearchRepository repository;

    @Test
    void 모든_단어를_포함하는_최신_정정과_취소_공고만_검색한다() {
        HousingComplex complex = persistComplex("서울 행복 단지", "37.5", "126.9");
        Announcement original = persistAnnouncement(null, "ORIGINAL", "서울 행복 원공고", "original");
        persistSupplyRow(original, complex, "original-row");
        Announcement correction = persistAnnouncement(
                original, "CORRECTION", "서울 행복 정정공고", "correction"
        );
        persistSupplyRow(correction, complex, "correction-row");
        Announcement cancelledOriginal = persistAnnouncement(
                null, "ORIGINAL", "서울 행복 다른 공고", "cancelled-original"
        );
        persistSupplyRow(cancelledOriginal, complex, "cancelled-original-row");
        Announcement cancellation = persistAnnouncement(
                cancelledOriginal, "CANCELLATION", "서울 행복 취소공고", "cancellation"
        );
        persistSupplyRow(cancellation, complex, "cancellation-row");
        entityManager.flush();

        var results = repository.findAnnouncements(condition("서울 행복", Set.of()), 20);

        assertThat(repository.findAnnouncements(condition("서울 행복", Set.of()), 1)).hasSize(1);
        assertThat(repository.countAnnouncements(condition("서울 행복", Set.of()))).isEqualTo(2);
        assertThat(repository.countAnnouncements(condition("서울 행복", Set.of(ApplicationStatus.CANCELLED))))
                .isEqualTo(1);
        assertThat(repository.countAnnouncements(condition("서울 없는단어", Set.of()))).isZero();
        assertThat(results).extracting(SearchSourceItem::id)
                .containsExactlyInAnyOrder(correction.getId().toString(), cancellation.getId().toString());
        assertThat(results).filteredOn(result -> "CANCELLED".equals(result.applicationStatus()))
                .singleElement()
                .extracting(SearchSourceItem::applicationStatus)
                .isEqualTo("CANCELLED");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.latitude()).isEqualByComparingTo("37.5");
            assertThat(result.longitude()).isEqualByComparingTo("126.9");
        });
    }

    @Test
    void 미확정_공고는_검색하지_않고_미확정_정정공고가_공개된_이전_공고를_가리지_않는다() {
        HousingComplex complex = persistComplex("서울 대기 단지", "37.5", "126.9");
        Announcement original = persistAnnouncement(null, "ORIGINAL", "서울 대기 원공고", "original");
        persistSupplyRow(original, complex, "original-row");
        Announcement correction = persistAnnouncement(original, "CORRECTION", "서울 대기 정정공고", "correction");
        persistSupplyRow(correction, complex, "correction-row");
        Announcement unconfirmed = Announcement.createUnconfirmed("SH:1", "서울 대기 SH 공고", AgencyCode.SH,
                LocalDate.of(2026, 8, 1), "https://example.com/sh/1");
        entityManager.persist(unconfirmed);
        persistSupplyRow(unconfirmed, complex, "unconfirmed-row");
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = :id")
                .setParameter("id", correction.getId()).executeUpdate();

        var results = repository.findAnnouncements(condition("서울 대기", Set.of()), 20);

        assertThat(results).extracting(SearchSourceItem::id).containsExactly(original.getId().toString());
        assertThat(repository.countAnnouncements(condition("서울 대기", Set.of()))).isEqualTo(1);
    }

    @Test
    void 공고가_없는_단지도_검색하고_취소_필터는_최신_취소공고_단지에만_적용한다() {
        HousingComplex withoutAnnouncement = persistComplex("서울 행복 무공고", "37.5", "126.9");
        HousingComplex cancelled = persistComplex("서울 행복 취소", "37.51", "126.91");
        Announcement original = persistAnnouncement(null, "ORIGINAL", "취소 전", "complex-original");
        persistSupplyRow(original, cancelled, "complex-original-row");
        Announcement cancellation = persistAnnouncement(
                original, "CANCELLATION", "취소 후", "complex-cancellation"
        );
        persistSupplyRow(cancellation, cancelled, "complex-cancellation-row");
        entityManager.flush();

        assertThat(repository.findComplexes(condition("서울 행복", Set.of()), 1)).hasSize(1);
        assertThat(repository.countComplexes(condition("서울 행복", Set.of()))).isEqualTo(2);
        assertThat(repository.countComplexes(condition("서울 행복", Set.of(ApplicationStatus.CANCELLED))))
                .isEqualTo(1);
        assertThat(repository.countComplexes(condition("서울 없는단어", Set.of()))).isZero();
        assertThat(repository.findComplexes(condition("서울 행복", Set.of()), 20))
                .extracting(SearchSourceItem::id)
                .containsExactlyInAnyOrder(
                        withoutAnnouncement.getId().toString(),
                        cancelled.getId().toString()
                );
        assertThat(repository.findComplexes(
                condition("서울 행복", Set.of(ApplicationStatus.CANCELLED)),
                20
        )).extracting(SearchSourceItem::id).containsExactly(cancelled.getId().toString());
    }

    @Test
    void 건수는_임대유형과_모집중_공고_조건을_반영하고_연결_행을_중복_집계하지_않는다() {
        HousingComplex complex = persistComplex("서울 행복 단지", "37.5", "126.9");
        persistComplex("서울 행복 무공고", "37.51", "126.91");
        Announcement announcement = persistAnnouncement(null, "ORIGINAL", "서울 행복 모집", "count");
        persistSupplyRow(announcement, complex, "count-row-1");
        persistSupplyRow(announcement, complex, "count-row-2");
        entityManager.flush();
        IntegratedSearchCondition active = new IntegratedSearchCondition(
                SearchMatch.from("서울 행복"), Set.of(RentalType.HAPPY_HOUSING),
                Set.of(ApplicationStatus.APPLYING), true, LocalDate.of(2026, 8, 15)
        );
        IntegratedSearchCondition otherRentalType = new IntegratedSearchCondition(
                SearchMatch.from("서울 행복"), Set.of(RentalType.NATIONAL_RENTAL),
                Set.of(), null, LocalDate.of(2026, 8, 15)
        );
        IntegratedSearchCondition withoutActive = new IntegratedSearchCondition(
                SearchMatch.from("서울 행복"), Set.of(), Set.of(), false, LocalDate.of(2026, 8, 15)
        );

        assertThat(repository.countAnnouncements(active)).isEqualTo(1);
        assertThat(repository.countComplexes(active)).isEqualTo(1);
        assertThat(repository.countAnnouncements(otherRentalType)).isZero();
        assertThat(repository.countComplexes(otherRentalType)).isZero();
        assertThat(repository.countComplexes(withoutActive)).isEqualTo(1);
    }

    @Test
    void 조건부_일정만_있는_공고와_단지는_확정_모집중_검색에서_제외한다() {
        HousingComplex complex = persistComplex("서울 행복 조건부", "37.5", "126.9");
        Announcement announcement = persistAnnouncement(null, "ORIGINAL", "서울 행복 조건부", "conditional");
        persistSupplyRow(announcement, complex, "conditional-row");
        LocalDate today = LocalDate.of(2026, 9, 1);
        announcement.confirmApplicationPeriod(today, today);
        entityManager.persist(AnnouncementApplicationSchedule.verified(announcement, complex, "후순위",
                ApplicationScheduleState.CONDITIONAL, "선순위 결과에 따라 진행", today, today, null, null,
                "https://apply.lh.or.kr/notice.pdf", 6));
        entityManager.flush();

        var conditional = condition("서울 행복", Set.of(ApplicationStatus.CONDITIONAL));
        assertThat(repository.findAnnouncements(conditional, 20)).singleElement()
                .extracting(SearchSourceItem::applicationStatus).isEqualTo("CONDITIONAL");
        assertThat(repository.countAnnouncements(conditional)).isEqualTo(1);
        assertThat(repository.findComplexes(conditional, 20)).singleElement()
                .extracting(SearchSourceItem::id).isEqualTo(complex.getId().toString());
        assertThat(repository.countAnnouncements(condition("서울 행복", Set.of(ApplicationStatus.APPLYING))))
                .isZero();
        assertThat(repository.countComplexes(new IntegratedSearchCondition(
                SearchMatch.from("서울 행복"), Set.of(), Set.of(), true, today))).isZero();
    }

    @Test
    void 같은_일치도의_공고는_진행_중_접수마감_과거_공고_순으로_보여주고_과거_공고를_표시한다() {
        HousingComplex complex = persistComplex("서울 순서 단지", "37.5", "126.9");
        Announcement past = persistAnnouncement("서울 순서 과거", "order-past", LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 8, 24));
        Announcement closed = persistAnnouncement("서울 순서 마감", "order-closed", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 25));
        Announcement applying = persistAnnouncement("서울 순서 접수", "order-applying", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 20));
        persistSupplyRow(past, complex, "order-past-row");
        persistSupplyRow(closed, complex, "order-closed-row");
        persistSupplyRow(applying, complex, "order-applying-row");
        entityManager.flush();

        var results = repository.findAnnouncements(condition("서울 순서", Set.of()), 20);

        assertThat(results).extracting(SearchSourceItem::id).containsExactly(applying.getId().toString(),
                closed.getId().toString(), past.getId().toString());
        assertThat(results).extracting(SearchSourceItem::pastAnnouncement).containsExactly(false, false, true);
    }

    @Test
    void 당첨자_발표_뒤_입주_일정이_7일_안에_끝났으면_과거_공고로_표시하지_않는다() {
        HousingComplex complex = persistComplex("서울 입주 단지", "37.5", "126.9");
        Announcement moveIn = persistAnnouncement("서울 입주 마감", "move-in-closed", LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 20), LocalDate.of(2026, 8, 20));
        entityManager.persist(AnnouncementSchedule.create(moveIn, ScheduleType.MOVE_IN, "입주 예정",
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 25, 18, 0), 0));
        persistSupplyRow(moveIn, complex, "move-in-closed-row");
        entityManager.flush();

        var results = repository.findAnnouncements(condition("서울 입주", Set.of()), 20);

        assertThat(results).extracting(SearchSourceItem::pastAnnouncement).containsExactly(false);
    }

    private Announcement persistAnnouncement(String name, String suffix, LocalDate postedDate,
            LocalDate applicationEndDate, LocalDate winnerAnnouncementDate) {
        Announcement announcement = Announcement.create(
                "source-" + suffix,
                null,
                null,
                name,
                "ORIGINAL",
                "행복주택",
                "신규모집",
                "LH",
                postedDate,
                applicationEndDate.minusDays(5),
                applicationEndDate,
                winnerAnnouncementDate,
                "https://example.com/" + suffix,
                null,
                0,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, null, null))
        );
        entityManager.persist(announcement);
        return announcement;
    }

    private IntegratedSearchCondition condition(String query, Set<ApplicationStatus> statuses) {
        return new IntegratedSearchCondition(
                SearchMatch.from(query),
                Set.of(),
                statuses,
                null,
                LocalDate.of(2026, 9, 1)
        );
    }

    private HousingComplex persistComplex(String name, String latitude, String longitude) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal(latitude),
                        new BigDecimal(longitude)
                ),
                100,
                "LH",
                LocalDate.of(2020, 1, 1),
                null,
                null,
                null,
                true,
                80,
                null,
                null
        );
        entityManager.persist(complex);
        return complex;
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String name,
            String suffix
    ) {
        Announcement announcement = Announcement.create(
                "source-" + suffix,
                previous == null ? null : previous.getSourceAnnouncementIdentifier(),
                previous,
                name,
                status,
                "행복주택",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 9, 1),
                "https://example.com/" + suffix,
                null,
                0,
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, null, null))
        );
        entityManager.persist(announcement);
        return announcement;
    }

    private void persistSupplyRow(Announcement announcement, HousingComplex complex, String sourceId) {
        entityManager.persist(SupplyRow.create(
                announcement,
                complex,
                null,
                sourceId,
                1,
                complex.getName(),
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                10
        ));
    }
}
