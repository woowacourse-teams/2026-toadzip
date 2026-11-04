package com.toadzip.backend.ingest.gh.refinement.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.gh.collection.service.GhAnnouncementCollectionService;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    @Autowired private HousingComplexRepository complexes;
    @MockitoBean private GhAnnouncementExternalRepository externalRepository;

    @BeforeEach
    void housingTypeDetails() {
        when(externalRepository.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM supply_rows WHERE announcement_id IN "
                + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'GH:%')");
        jdbc.update("DELETE FROM announcement_complexes WHERE announcement_id IN "
                + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'GH:%')");
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id IN "
                + "(SELECT id FROM announcements WHERE source_announcement_identifier LIKE 'GH:%')");
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier LIKE 'GH:%'");
        jdbc.update("DELETE FROM housing_complexes WHERE name IN "
                + "('다산 센트럴파크 6단지', '다른 시의 같은 지번', '같은 지번 영구임대', 'LH 단지', '다산 센트럴파크 6단지 2')");
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

        changeSupplyCount();
        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        var report = refinement.refineAll();
        assertThat(report.updated()).isZero();
        assertThat(report.reviewRequired()).isOne();

        var announcement = announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow();
        assertThat(announcement.getName()).isEqualTo(ROW.title());
        assertThat(announcement.isSourceReviewRequired()).isTrue();
        assertThat(supplyRows("GH:801")).extracting(row -> row.get("total_supply_household_count"))
                .containsExactly(120, 60);
    }

    @Test
    void 공개_전이라도_관리자가_고친_공고는_정정된_제목으로_덮지_않는다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("UPDATE announcements SET admin_modified = true WHERE source_announcement_identifier = 'GH:801'");

        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        refinement.refineAll();

        var announcement = announcements.findBySourceAnnouncementIdentifier("GH:801").orElseThrow();
        assertThat(announcement.getName()).isEqualTo(ROW.title());
        assertThat(announcement.isSourceReviewRequired()).isTrue();
    }

    @Test
    void 같은_이름의_지구가_두_번_나와도_한_공고_단지로_보고_원천에서_빠진_지구는_표시하고_그_행을_없앤다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("""
                UPDATE gh_announcement_source SET districts = replace(districts, '"안양관양 경기행복주택"',
                    '"다산 센트럴파크6단지"') WHERE pbanc_no = '801'
                """);

        assertThat(refinement.refineAll().failed()).isZero();

        assertThat(jdbc.queryForList("""
                SELECT c.source_complex_name || ':' || c.present_in_source FROM announcement_complexes c
                JOIN announcements a ON a.id = c.announcement_id
                WHERE a.source_announcement_identifier = 'GH:801' ORDER BY c.id
                """, String.class)).containsExactly("다산 센트럴파크6단지:true", "안양관양 경기행복주택:false");
        assertThat(supplyRows("GH:801")).hasSize(2);
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

    @Test
    void 지구의_지번_시군구_공급유형이_맞는_GH_단지가_하나뿐이면_공고_단지에_미리_고른다() {
        long dasan = complex("다산 센트럴파크 6단지", "경기도 남양주시 다산순환로 20", "4136010100161070000", "GH",
                "NATIONAL_RENTAL");
        complex("다른 시의 같은 지번", "경기도 구리시 갈매순환로 1", "4131010100161070000", "GH", "NATIONAL_RENTAL");
        complex("같은 지번 영구임대", "경기도 남양주시 다산순환로 20", "4136010100161070000", "GH", "PERMANENT_RENTAL");
        complex("LH 단지", "경기도 안양시 동안구 관양로 1", "4117310400114980021", "LH", "NATIONAL_RENTAL");
        collect(ROW);

        refinement.refineAll();

        var linked = linkedComplexes("GH:801");
        assertThat(linked).containsEntry("다산 센트럴파크6단지", dasan);
        assertThat(linked).containsEntry("안양관양 경기행복주택", null);
        assertThat(jdbc.queryForObject("""
                SELECT link_method FROM announcement_complexes c JOIN announcements a ON a.id = c.announcement_id
                WHERE a.source_announcement_identifier = 'GH:801' AND c.source_complex_name = '다산 센트럴파크6단지'
                """, String.class)).isEqualTo("AUTO");
        assertThat(jdbc.queryForObject("""
                SELECT total_household_count FROM announcement_complexes c JOIN announcements a ON a.id = c.announcement_id
                WHERE a.source_announcement_identifier = 'GH:801' AND c.source_complex_name = '다산 센트럴파크6단지'
                """, Integer.class)).isNull();
    }

    @Test
    void 같은_조건의_GH_단지가_둘이면_고르지_않는다() {
        complex("다산 센트럴파크 6단지", "경기도 남양주시 다산순환로 20", "4136010100161070000", "GH", "NATIONAL_RENTAL");
        complex("다산 센트럴파크 6단지 2", "경기도 남양주시 다산순환로 22", "4136010200161070000", "GH", "NATIONAL_RENTAL");
        collect(ROW);

        refinement.refineAll();

        assertThat(linkedComplexes("GH:801")).containsEntry("다산 센트럴파크6단지", null);
    }

    @Test
    void 지구를_못_읽은_원천은_공고_단지를_빠진_것으로_보지_않고_다시_정제해도_바꾸지_않는다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("UPDATE gh_announcement_source SET districts = NULL WHERE pbanc_no = '801'");

        refinement.refineAll();

        assertThat(jdbc.queryForList("""
                SELECT present_in_source FROM announcement_complexes c JOIN announcements a ON a.id = c.announcement_id
                WHERE a.source_announcement_identifier = 'GH:801'
                """, Boolean.class)).containsOnly(true).hasSize(2);
    }

    @Test
    void 지구의_주택형마다_미리_고른_단지를_가리키는_공급행_틀을_만들고_같은_원천이면_다시_쓰지_않는다() {
        long dasan = complex("다산 센트럴파크 6단지", "경기도 남양주시 다산순환로 20", "4136010100161070000", "GH",
                "NATIONAL_RENTAL");
        collect(ROW);

        refinement.refineAll();

        var rows = supplyRows("GH:801");
        assertThat(rows).extracting(row -> row.get("source_supply_row_identifier"))
                .containsExactly("GH:801:다산 센트럴파크6단지:33A", "GH:801:다산 센트럴파크6단지:36A");
        assertThat(rows).extracting(row -> row.get("source_housing_type_name")).containsExactly("33A", "36A 청년");
        assertThat(rows).extracting(row -> row.get("total_supply_household_count")).containsExactly(120, 60);
        assertThat(rows).extracting(row -> row.get("housing_complex_id")).containsOnly(dasan);
        assertThat(rows).extracting(row -> row.get("housing_type_id")).containsOnly((Object) null);

        refinement.refineAll();
        assertThat(supplyRows("GH:801")).extracting(row -> row.get("id"))
                .containsExactlyElementsOf(rows.stream().map(row -> row.get("id")).toList());
    }

    @Test
    void 관리자가_PDF_보정을_한_공고는_원천_주택형이_바뀌어도_공급행_틀을_다시_쓰지_않는다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("UPDATE announcements SET admin_updated_at = now() WHERE source_announcement_identifier = 'GH:801'");
        jdbc.update("""
                UPDATE gh_announcement_source SET districts = replace(districts, '"supplyCount":"120"',
                    '"supplyCount":"90"') WHERE pbanc_no = '801'
                """);

        refinement.refineAll();

        assertThat(supplyRows("GH:801")).extracting(row -> row.get("total_supply_household_count"))
                .containsExactly(120, 60);
    }

    @Test
    void 관리자가_손대지_않은_공고는_원천_세대수가_바뀌면_틀을_원천에_맞춘다() {
        collect(ROW);
        refinement.refineAll();
        jdbc.update("""
                UPDATE gh_announcement_source SET districts = replace(districts, '"supplyCount":"120"',
                    '"supplyCount":"90"') WHERE pbanc_no = '801'
                """);

        refinement.refineAll();

        assertThat(supplyRows("GH:801")).extracting(row -> row.get("total_supply_household_count"))
                .containsExactly(90, 60);
    }

    private void changeSupplyCount() {
        jdbc.update("""
                UPDATE gh_announcement_source SET districts = replace(districts, '"supplyCount":"120"',
                    '"supplyCount":"90"') WHERE pbanc_no = '801'
                """);
    }

    private List<Map<String, Object>> supplyRows(String identifier) {
        return jdbc.queryForList("""
                SELECT r.id, r.source_supply_row_identifier, r.source_housing_type_name, r.total_supply_household_count,
                    r.housing_complex_id, r.housing_type_id
                FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
                WHERE a.source_announcement_identifier = ? ORDER BY r.display_order
                """, identifier);
    }

    private long complex(String name, String roadAddress, String pnu, String provider, String supplyType) {
        var complex = HousingComplex.create(name, UUID.randomUUID().toString(), supplyType,
                Address.create(roadAddress, pnu, pnu.substring(0, 10), pnu.substring(0, 2), pnu.substring(0, 5),
                        new BigDecimal("37.600000"), new BigDecimal("127.150000")),
                100, provider, LocalDate.of(2020, 6, 30), "INDIVIDUAL", "APARTMENT", "STAIR", true, 80, null, 0);
        return complexes.saveAndFlush(complex).getId();
    }

    private Map<String, Long> linkedComplexes(String identifier) {
        Map<String, Long> linked = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.source_complex_name, c.housing_complex_id FROM announcement_complexes c
                JOIN announcements a ON a.id = c.announcement_id WHERE a.source_announcement_identifier = ?
                ORDER BY c.id
                """, row -> {
            linked.put(row.getString(1), (Long) row.getObject(2));
        }, identifier);
        return linked;
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
