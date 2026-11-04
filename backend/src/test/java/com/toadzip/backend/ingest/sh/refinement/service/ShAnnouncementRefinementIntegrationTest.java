package com.toadzip.backend.ingest.sh.refinement.service;

import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.detail;
import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.mainList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementOrigin;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementStore;
import com.toadzip.backend.ingest.sh.collection.service.ShAnnouncementCollectionService;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(IngestExecutionExtension.class)
@SpringBootTest(properties = {"ingest.sh.request-interval=0s", "ingest.sh.lookback-days=0"})
@ActiveProfiles("test")
class ShAnnouncementRefinementIntegrationTest {

    private static final String SCHEDULE = """
            ■ 공급호수: 신규공급 154호, 재공급 1,330호(예비입주자 모집 포함)
            ■ 접수일
            ○ 인터넷 접수: 2026. 9. 9.(수) 10:00 ~ 9. 11.(금) 17:00
            ○ 방문 접수: 2026. 9. 10.(목) ~ 9. 11.(금) 10:00 ~ 17:00
            ■ 당첨자발표: 2027.01.29.(금) 예정
            """;

    @Autowired private ShAnnouncementRefinementService refinement;
    @Autowired private ShAnnouncementCollectionService collection;
    @Autowired private ShAnnouncementSourceRepository sources;
    @Autowired private ShAnnouncementStore store;
    @Autowired private ExternalDataCollectionFailureRepository failures;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ShAnnouncementExternalRepository externalRepository;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id IN "
                + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'SH:%')");
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier LIKE 'SH:%'");
        sources.deleteAll();
        failures.deleteAll();
    }

    @Test
    void SH_원천으로_PDF_공고문만_붙인_미확정_공고를_만들고_같은_원천이면_다시_바꾸지_않으며_알리지_않는다() {
        collect();
        long outboxBefore = count("notification_outbox_events");

        assertThat(refinement.refineAll().created()).isOne();

        Announcement announcement = sh100();
        assertThat(announcement.getReleaseStatus().name()).isEqualTo("UNCONFIRMED");
        assertThat(announcement.getName()).isEqualTo("주택 입주자 모집");
        assertThat(announcement.getPostedDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(announcement.getOriginalUrl()).isEqualTo(ShAnnouncementExternalRepository.detailUrl("100"));
        assertThat(announcement.getProvider().name()).isEqualTo("SH");
        assertThat(announcement.origin()).isEqualTo(AnnouncementOrigin.SH_SOURCE);
        assertThat(announcement.getApplicationStartDate()).isNull();
        assertThat(announcement.getWinnerAnnouncementDate()).isNull();
        assertThat(jdbc.queryForList("SELECT file_name FROM announcement_attachments WHERE announcement_id = ?",
                String.class, announcement.getId())).containsExactly("공고문.pdf");
        assertThat(count("notification_outbox_events")).isEqualTo(outboxBefore);

        long version = announcement.getVersion();
        var again = refinement.refineAll();
        assertThat(again.created() + again.updated()).isZero();
        assertThat(sh100().getVersion()).isEqualTo(version);
    }

    @Test
    void 본문에서_읽은_접수기간과_당첨자_발표일을_채우고_본문이_바뀌면_따라간다() {
        collect();
        setBody(SCHEDULE);

        refinement.refineAll();
        assertThat(sh100().getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(sh100().getApplicationEndDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(sh100().getWinnerAnnouncementDate()).isEqualTo(LocalDate.of(2027, 1, 29));

        setBody(SCHEDULE.replace("2027.01.29.", "2027.02.05."));
        assertThat(refinement.refineAll().updated()).isOne();
        assertThat(sh100().getWinnerAnnouncementDate()).isEqualTo(LocalDate.of(2027, 2, 5));

        setBody(SCHEDULE.replace("■ 당첨자발표: 2027.01.29.(금) 예정\n", ""));
        refinement.refineAll();
        assertThat(sh100().getWinnerAnnouncementDate()).isNull();
        assertThat(sh100().getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void 관리자가_고친_공고는_본문_일정과_제목으로_덮지_않고_원천_확인이_필요하다고_표시한다() {
        collect();
        setBody(SCHEDULE);
        refinement.refineAll();
        jdbc.update("UPDATE announcements SET admin_modified = true WHERE source_announcement_identifier = 'SH:100'");

        setBody(SCHEDULE.replace("2027.01.29.", "2027.02.05."));
        jdbc.update("UPDATE sh_announcement_source SET title = '[정정] 주택 입주자 모집' WHERE seq = '100'");
        refinement.refineAll();

        assertThat(sh100().getWinnerAnnouncementDate()).isEqualTo(LocalDate.of(2027, 1, 29));
        assertThat(sh100().getName()).isEqualTo("주택 입주자 모집");
        assertThat(sh100().isSourceReviewRequired()).isTrue();
    }

    @Test
    void 관리자가_일정을_확인한_공고나_공개된_공고는_본문_일정으로_덮지_않는다() {
        collect();
        setBody(SCHEDULE);
        refinement.refineAll();
        jdbc.update("""
                UPDATE announcements SET application_schedule_reviewed = true
                WHERE source_announcement_identifier = 'SH:100'
                """);
        setBody(SCHEDULE.replace("2026. 9. 9.(수)", "2026. 9. 1.(화)"));
        refinement.refineAll();
        assertThat(sh100().getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(sh100().isSourceReviewRequired()).isTrue();

        jdbc.update("""
                UPDATE announcements SET application_schedule_reviewed = false, source_review_required = false,
                    release_status = 'PUBLISHED',
                    supply_type = 'HAPPY_HOUSING', recruitment_type = 'NEW'
                WHERE source_announcement_identifier = 'SH:100'
                """);
        refinement.refineAll();
        assertThat(sh100().getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(sh100().isSourceReviewRequired()).isTrue();
    }

    @Test
    void 공개된_공고의_본문에서_일정을_못_읽으면_원천_확인이_필요하다고_보지_않는다() {
        collect();
        setBody(SCHEDULE);
        refinement.refineAll();
        jdbc.update("""
                UPDATE announcements SET release_status = 'PUBLISHED', supply_type = 'HAPPY_HOUSING',
                    recruitment_type = 'NEW' WHERE source_announcement_identifier = 'SH:100'
                """);

        setBody("입주자 모집 안내");
        assertThat(refinement.refineAll().reviewRequired()).isZero();

        assertThat(sh100().isSourceReviewRequired()).isFalse();
        assertThat(sh100().getWinnerAnnouncementDate()).isEqualTo(LocalDate.of(2027, 1, 29));
    }

    @Test
    void 휴지통에_넣은_공고는_다시_만들거나_바꾸지_않는다() {
        collect();
        refinement.refineAll();
        jdbc.update("UPDATE announcements SET admin_deleted = true WHERE source_announcement_identifier = 'SH:100'");
        setBody(SCHEDULE);

        assertThat(refinement.refineAll().created()).isZero();
        assertThat(count("announcements WHERE source_announcement_identifier = 'SH:100'")).isOne();
        assertThat(sh100().getApplicationStartDate()).isNull();
    }

    @Test
    void 한_원천을_정제하지_못해도_다른_원천은_공고로_만든다() {
        collect();
        store.store(new ShAnnouncementSnapshot("200", "다른 입주자 모집", "공급부", LocalDate.of(2026, 10, 7),
                ShAnnouncementExternalRepository.detailUrl("200"), ShAnnouncementExternalRepository.LIST_URL,
                SCHEDULE, "[]"));
        jdbc.update("UPDATE sh_announcement_source SET attachments = 'not json' WHERE seq = '100'");

        var report = refinement.refineAll();

        assertThat(report.failed()).isOne();
        assertThat(report.created()).isOne();
        assertThat(announcements.findBySourceAnnouncementIdentifier("SH:100")).isEmpty();
        assertThat(announcements.findBySourceAnnouncementIdentifier("SH:200")).isPresent();
    }

    private void collect() {
        var document = Jsoup.parse(mainList());
        document.select("#listTb tbody > tr").get(1).remove();
        document.selectFirst("#mainform .mentcount strong").text("1");
        when(externalRepository.fetchList(1)).thenReturn(document.html());
        when(externalRepository.fetchDetail("100")).thenReturn(detail());
        collection.collect();
    }

    private void setBody(String body) {
        jdbc.update("UPDATE sh_announcement_source SET body_text = ? WHERE seq = '100'", body);
    }

    private Announcement sh100() {
        return announcements.findBySourceAnnouncementIdentifier("SH:100").orElseThrow();
    }

    private long count(String tableAndCondition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tableAndCondition, Long.class);
    }
}
