package com.toadzip.backend.ingest.gh.refinement.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.gh.collection.service.GhAnnouncementCollectionService;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(IngestExecutionExtension.class)
@SpringBootTest(properties = {"ingest.gh.request-interval=0s", "ingest.gh.lookback-days=0"})
@ActiveProfiles("test")
class GhAnnouncementRefinementIntegrationTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");
    private static final Row OTHER = new Row("802", "행복주택", "안양관양 행복주택 입주자 모집공고", "2026-07-11");

    @Autowired private GhAnnouncementRefinementService refinement;
    @Autowired private GhAnnouncementCollectionService collection;
    @Autowired private GhAnnouncementSourceRepository sources;
    @Autowired private ExternalDataCollectionFailureRepository failures;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GhAnnouncementExternalRepository externalRepository;

    @BeforeEach
    void housingTypeDetails() {
        when(externalRepository.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id IN "
                + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'GH:%')");
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier LIKE 'GH:%'");
        sources.deleteAll();
        failures.deleteAll();
    }

    @Test
    void GH_원천으로_미확정_공고와_PDF_공고문을_만들고_같은_원천이면_다시_바꾸지_않으며_알리지_않는다() {
        collect(ROW);
        long outboxBefore = count("notification_outbox_events");

        var report = refinement.refineAll();

        assertThat(report.created()).isOne();
        var announcement = announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow();
        assertThat(announcement.getReleaseStatus().name()).isEqualTo("UNCONFIRMED");
        assertThat(announcement.getName()).isEqualTo(ROW.title());
        assertThat(announcement.getPostedDate()).hasToString("2026-07-10");
        assertThat(announcement.getOriginalUrl()).isEqualTo(RENTAL_HOUSE.detailUrl("801", "01"));
        assertThat(announcement.getProvider().name()).isEqualTo("GH");
        assertThat(jdbc.queryForList("SELECT file_name FROM announcement_attachments WHERE announcement_id = ?",
                String.class, announcement.getId())).containsExactly("모집공고문.pdf");
        assertThat(count("notification_outbox_events")).isEqualTo(outboxBefore);

        long version = announcement.getVersion();
        var again = refinement.refineAll();
        assertThat(again.created()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow().getVersion())
                .isEqualTo(version);
        assertThat(count("announcement_attachments WHERE announcement_id = " + announcement.getId())).isOne();
    }

    @Test
    void 관리자가_손대지_않은_미확정_공고는_정정된_제목을_따른다() {
        collect(ROW);
        refinement.refineAll();

        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        assertThat(refinement.refineAll().updated()).isOne();

        assertThat(announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow().getName())
                .isEqualTo("[정정] " + ROW.title());
    }

    @Test
    void 공개된_공고는_정정된_제목으로_덮지_않고_원천_확인이_필요하다고_표시한다() {
        collect(ROW);
        refinement.refineAll();
        // 필드를 고치지 않고 공개만 한 공고(관리자 수정 표시 없음)도 정제가 덮지 않아야 한다.
        jdbc.update("""
                UPDATE announcements SET release_status = 'PUBLISHED', supply_type = 'NATIONAL_RENTAL',
                    recruitment_type = 'NEW', application_start_date = DATE '2026-07-20',
                    application_end_date = DATE '2026-07-22', winner_announcement_date = DATE '2026-09-01'
                WHERE source_announcement_identifier = 'GH:801'
                """);

        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        assertThat(refinement.refineAll().updated()).isZero();

        var announcement = announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow();
        assertThat(announcement.getName()).isEqualTo(ROW.title());
        assertThat(announcement.isSourceReviewRequired()).isTrue();
    }

    @Test
    void 휴지통에_넣은_공고는_다시_만들거나_바꾸지_않는다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("UPDATE announcements SET admin_deleted = true WHERE source_announcement_identifier = 'GH:801'");

        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        var report = refinement.refineAll();

        assertThat(report.created()).isZero();
        assertThat(count("announcements WHERE source_announcement_identifier = 'GH:801'")).isOne();
        assertThat(announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow().getName())
                .isEqualTo(ROW.title());
    }

    @Test
    void 첨부를_못_읽은_원천은_이미_붙인_공고문을_지우지_않는다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("UPDATE gh_announcement_source SET attachments = NULL WHERE pbanc_no = '801'");

        refinement.refineAll();

        long id = announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow().getId();
        assertThat(count("announcement_attachments WHERE announcement_id = " + id)).isOne();
    }

    @Test
    void 한_원천을_정제하지_못해도_다른_원천은_공고로_만든다() {
        collect(ROW, OTHER);
        jdbc.update("UPDATE gh_announcement_source SET attachments = 'not json' WHERE pbanc_no = '801'");

        var report = refinement.refineAll();

        assertThat(report.failed()).isOne();
        assertThat(report.created()).isOne();
        assertThat(announcements.findBySourceAnnouncementIdentifier("GH:801")).isEmpty();
        assertThat(announcements.findBySourceAnnouncementIdentifier("GH:802")).isPresent();
    }

    private void collect(Row... rows) {
        when(externalRepository.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, rows.length, rows));
        for (Row row : rows) {
            when(externalRepository.fetchDetail(RENTAL_HOUSE, row.pbancNo(), "01"))
                    .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, row));
        }
        collection.collect();
    }

    private long count(String tableAndCondition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tableAndCondition, Long.class);
    }
}
