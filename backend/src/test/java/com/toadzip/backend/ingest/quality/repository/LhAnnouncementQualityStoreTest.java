package com.toadzip.backend.ingest.quality.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.CollectedSourceRows;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionPolicy;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.service.LhAnnouncementQualityService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({CollectedSourceRows.class,
        MyHomeAnnouncementSourceFixtures.class,
        MyHomeAnnouncementSourceReader.class,
        LhAnnouncementCatalogSourceReader.class,
        LhAnnouncementQualityStore.class, LhAnnouncementQualityService.class,
        LhAnnouncementCollectedAtReader.class,
        LhAnnouncementCollectionProgressStore.class, LhAnnouncementCollectionCandidateResolver.class,
        LhAnnouncementCollectionPolicy.class,
        LhAnnouncementQualityStoreTest.TimeConfiguration.class})
class LhAnnouncementQualityStoreTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-28T09:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LhAnnouncementQualityService service;

    @Autowired
    private LhAnnouncementCollectionProgressStore progressStore;

    @Autowired
    private CollectedSourceRows fixtures;

    @Autowired
    private MyHomeAnnouncementSourceFixtures sources;

    @Test
    void 수집_기록과_제품_충족률을_서로_다른_분모로_집계한다() {
        long announcementId = jdbc.sql("""
                INSERT INTO announcements
                    (application_end_date, application_start_date, name, original_url, posted_date,
                     provider, recruitment_type, source_announcement_identifier, status, supply_type,
                     view_count, winner_announcement_date, application_schedule_reviewed, lh_pan_id_reviewed,
                     lh_reception_place_owned)
                VALUES (DATE '2026-10-02', DATE '2026-10-01', '품질 확인 공고', 'https://apply.lh.or.kr',
                        DATE '2026-09-28', 'LH', 'NEW', 'quality-test-announcement', 'ORIGINAL',
                        'PERMANENT_RENTAL', 0, DATE '2026-10-10', true, false, false)
                RETURNING id
                """).query(Long.class).single();
        long linkedRow = row(announcementId, "linked", null);
        row(announcementId, "unlinked", "주택형 후보 모호");
        jdbc.sql("""
                INSERT INTO supply_targets (supply_row_id, target, display_order, rental_deposit, monthly_rent,
                                            lh_amount_preserved_reason, source_supply_target_identifier)
                VALUES (:rowId, '전체', 1, 10000000, 0, 'LH_AMOUNT_NOT_PROVIDED', 'LH:quality-pan:SUPPLY:0:TARGET')
                """).param("rowId", linkedRow).update();
        checkpoint("quality-pan", OBSERVED_AT.minusSeconds(600));
        jdbc.sql("""
                INSERT INTO external_data_collection_failures
                    (source, request_description, occurred_at, attempt_count, status, last_occurred_at,
                     occurrence_count, recurrence_count, error_type, reason)
                VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'quality-held-request', :occurredAt, 0, 'PENDING',
                        :occurredAt, 1, 0, 'IncompleteLhSupplyReplacementException', '공급행 감소')
                """).param("occurredAt", java.sql.Timestamp.from(OBSERVED_AT)).update();
        catalog("quality-pan", OBSERVED_AT.minusSeconds(86400));
        source("myhome-1", "quality-pan", null);
        progressStore.link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "myhome-1",
                request("previous-pan").requestDescription(), "previous-pan");

        LhAnnouncementQualityResponse result = service.snapshot();

        assertThat(result.connection().total()).isEqualTo(2);
        assertThat(result.connection().housingTypeLinked()).isZero();
        assertThat(result.connection().unlinkedReasons()).containsEntry("주택형 후보 모호", 1L);
        assertThat(result.amounts()).isEqualTo(new LhAnnouncementQualityResponse.Coverage(2, 1));
        assertThat(result.schedules().reviewed()).isOne();
        assertThat(result.supplyCollection().collectedRequests()).isOne();
        assertThat(result.unlinkedLhLeaseCatalogCount()).isOne();
        assertThat(result.unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::panId)
                .containsExactly("quality-pan");
        assertThat(result.preservedSourceRequestCount()).isOne();
        assertThat(result.preservedAmountTargetCount()).isOne();
        assertThat(result.preservedAmountReasons()).containsEntry("LH_AMOUNT_NOT_PROVIDED", 1L);
        assertThat(result.heldRequests()).extracting(LhAnnouncementQualityResponse.HeldRequest::requestDescription)
                .contains("quality-held-request");

        progressStore.link(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "myhome-1",
                request("quality-pan").requestDescription(), "quality-pan");
        assertThat(service.snapshot().unlinkedLhLeaseCatalogCount()).isZero();
    }

    @Test
    void 원천_확보는_현재_요청을_중복없이_세고_최초_실패도_분모에_포함한다() {
        source("current", "current-pan", null);
        source("same-request", "current-pan", null);
        source("never-successful", "missing-pan", null);
        source("ended", "ended-pan", "20260101");
        source("inactive", "inactive-pan", null);
        jdbc.sql("UPDATE myhome_announcement_source_rows SET active = false WHERE pblanc_id = 'inactive'").update();
        checkpoint("current-pan", OBSERVED_AT.minusSeconds(600));
        checkpoint("ended-pan", OBSERVED_AT.minusSeconds(600));
        checkpoint("inactive-pan", OBSERVED_AT.minusSeconds(600));

        assertThat(service.snapshot().supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(2, 1, OBSERVED_AT.minusSeconds(600)));
        assertThat(service.snapshot().detailCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(2, 0, null));
    }

    @Test
    void 최근_종료_공고의_원천_확보는_실제_수집_시각으로_확인한다() {
        source("recent-ended", "recent-pan", "20260927");
        checkpoint("recent-pan", OBSERVED_AT.minusSeconds(43200));

        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();
    }

    @Test
    void 현재_회차의_원천만_사용하고_충돌하는_공고는_연결_성공으로_세지_않는다() {
        source("changed", "old-pan", null);
        jdbc.sql("UPDATE myhome_announcement_source_rows SET collected_at = :oldAt, last_seen_run_id = 'old'")
                .param("oldAt", java.sql.Timestamp.from(OBSERVED_AT.minusSeconds(86400))).update();
        source("changed", "new-pan", null);
        checkpoint("old-pan", OBSERVED_AT.minusSeconds(60));
        source("conflict", "conflict-a", null);
        source("conflict", "conflict-b", null);

        assertThat(service.snapshot().supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(1, 0, null));
    }

    @Test
    void 목록이_변경돼도_기존_성공_원천_확보와_수집_시각은_보존한다() {
        source("stable", "stable-pan", "20261020");
        catalog("stable-pan", OBSERVED_AT.minusSeconds(86400));
        checkpoint("stable-pan", OBSERVED_AT.minusSeconds(43200));

        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();

        jdbc.sql("UPDATE lh_announcement_catalog_entries SET changed_at = :changedAt")
                .param("changedAt", java.sql.Timestamp.from(OBSERVED_AT.minusSeconds(60))).update();
        entityManager.clear();
        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();
    }

    @Test
    void 같은_요청을_여러_공고가_사용해도_성공_원천은_한_번만_센다() {
        source("stable", "shared-pan", "20261020");
        source("urgent", "shared-pan", "20260929");
        catalog("shared-pan", OBSERVED_AT.minusSeconds(86400));
        checkpoint("shared-pan", OBSERVED_AT.minusSeconds(43200));

        assertThat(service.snapshot().supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(1, 1, OBSERVED_AT.minusSeconds(43200)));
    }

    private void catalog(String panId, Instant changedAt) {
        jdbc.sql("""
                INSERT INTO lh_announcement_catalog_entries
                    (source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
                     announcement_type_code, supply_info_type_code, raw_payload,
                     changed_at, collected_at, present_in_latest_catalog, version, query_start_date, query_end_date, last_collection_record_id)
                VALUES (:panId, :panId, '03', '06', '07', '062', '{}', :changedAt, :now, true, 0, '20260901', '20260928', :record)
                """).param("panId", panId)
                .param("record", fixtures.record("LH_ANNOUNCEMENT_CATALOG"))
                .param("changedAt", java.sql.Timestamp.from(changedAt))
                .param("now", java.sql.Timestamp.from(OBSERVED_AT)).update();
    }

    private void source(String pblancId, String panId, String endDate) {
        var values = new java.util.HashMap<String, Object>();
        values.put("pblancId", pblancId);
        values.put("houseSn", 1);
        values.put("suplyInsttNm", "LH");
        values.put("suplyTyNm", "국민임대");
        values.put("url", "https://apply.lh.or.kr/notice?panId=" + panId
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        values.put("endDe", endDate);
        var snapshot = tools.jackson.databind.json.JsonMapper.builder().build().convertValue(values,
                MyHomeAnnouncementSourceSnapshot.class);
        var row = MyHomeAnnouncementSource.from(0, snapshot);
        row.markSeen("current", OBSERVED_AT);
        sources.save(row);
    }

    private void checkpoint(String panId, Instant completedAt) {
        fixtures.querySource("LH_ANNOUNCEMENT_SUPPLY", request(panId).requestDescription(), completedAt);
    }

    private LhAnnouncementRequest request(String panId) {
        return new LhAnnouncementRequest(panId, "03", "06", "07", "062");
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
        }
    }

    private long row(long announcementId, String key, String failureReason) {
        return jdbc.sql("""
                INSERT INTO supply_rows
                    (announcement_id, display_order, source_supply_row_identifier, source_complex_name,
                     source_housing_type_name, supply_pnu, supply_category, matching_failure_reason,
                     lh_total_supply_household_count_enriched, lh_total_supply_household_count_owned)
                VALUES (:announcementId, 1, :key, '단지', '주택형', '0000000000000000000', 'NEW_SUPPLY',
                        :reason, false, false)
                RETURNING id
                """).param("announcementId", announcementId).param("key", key)
                .param("reason", failureReason).query(Long.class).single();
    }
}
