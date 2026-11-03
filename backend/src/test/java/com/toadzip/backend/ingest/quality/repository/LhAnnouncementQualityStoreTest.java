package com.toadzip.backend.ingest.quality.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.CollectedSourceRows;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectedAtReader;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionPolicy;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.service.LhAnnouncementQualityService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

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
                """).param("occurredAt", Timestamp.from(OBSERVED_AT)).update();
        catalog("quality-pan", OBSERVED_AT.minusSeconds(86400));
        progressStore.save(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, catalogKey("quality-pan"),
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

        progressStore.save(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, catalogKey("quality-pan"),
                request("quality-pan").requestDescription(), "quality-pan");
        assertThat(service.snapshot().unlinkedLhLeaseCatalogCount()).isZero();
    }

    @Test
    void 보류된_공급_교체만_집계하고_사유에서_제안_지문을_읽는다() {
        String fingerprint = "c".repeat(64);
        failure("LH_ANNOUNCEMENT_SUPPLY", "incomplete", "PENDING", "IncompleteLhSupplyReplacementException",
                "LH 공급 수집 결과에 기존 공급행 2건이 누락되어 원천을 교체하지 않습니다. proposedFingerprint="
                        + fingerprint, 5);
        failure("LH_ANNOUNCEMENT_SUPPLY", "empty", "PENDING", "EmptyLhSupplyReplacementException",
                "기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.", 4);
        failure("LH_ANNOUNCEMENT_SUPPLY", "no-fingerprint", "PENDING", "IncompleteLhSupplyReplacementException",
                "LH 공급 수집 결과에 기존 공급행 1건이 누락되어 원천을 교체하지 않습니다.", 3);
        failure("LH_ANNOUNCEMENT_SUPPLY", "request-failure", "PENDING", "ExternalDataRequestException",
                "proposedFingerprint=" + fingerprint, 2);
        failure("LH_ANNOUNCEMENT_SUPPLY", "resolved", "RESOLVED", "EmptyLhSupplyReplacementException",
                "기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.", 1);
        failure("LH_ANNOUNCEMENT_DETAIL", "detail", "PENDING", "EmptyLhSupplyReplacementException",
                "기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.", 0);

        LhAnnouncementQualityResponse result = service.snapshot();

        assertThat(result.preservedSourceRequestCount()).isEqualTo(3);
        assertThat(result.preservedReasons()).containsExactly(
                entry("EmptyLhSupplyReplacementException", 1L),
                entry("IncompleteLhSupplyReplacementException", 2L));
        assertThat(result.heldRequests()).extracting(
                        LhAnnouncementQualityResponse.HeldRequest::requestDescription,
                        LhAnnouncementQualityResponse.HeldRequest::proposedFingerprint)
                .containsExactly(
                        tuple("no-fingerprint", null),
                        tuple("empty", LhSupplySnapshot.fingerprint(List.of())),
                        tuple("incomplete", fingerprint));
    }

    @Test
    void 마이홈_원천이_없는_LH_공고도_분모에_넣고_수집_연결되면_조사_후보에서_뺀다() {
        catalog("lh-only-pan", OBSERVED_AT.minusSeconds(86400));

        LhAnnouncementQualityResponse before = service.snapshot();
        assertThat(before.supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(1, 0, null));
        assertThat(before.unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::panId)
                .containsExactly("lh-only-pan");

        checkpoint("lh-only-pan", OBSERVED_AT.minusSeconds(600));
        progressStore.save(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, catalogKey("lh-only-pan"),
                request("lh-only-pan").requestDescription(), "lh-only-pan");

        LhAnnouncementQualityResponse after = service.snapshot();
        assertThat(after.supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(1, 1, OBSERVED_AT.minusSeconds(600)));
        assertThat(after.unlinkedLhLeaseCatalogCount()).isZero();
        assertThat(after.unlinkedLhCandidates()).isEmpty();
    }

    @Test
    void 지난_마이홈_공고_원천과_연결은_원천_확보와_조사_후보에_쓰지_않는다() {
        source("myhome-only", "myhome-pan", null);
        checkpoint("myhome-pan", OBSERVED_AT.minusSeconds(600));
        catalog("catalog-pan", OBSERVED_AT.minusSeconds(86400));
        source("myhome-linked", "catalog-pan", null);
        progressStore.save(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "myhome-linked",
                request("catalog-pan").requestDescription(), "catalog-pan");

        LhAnnouncementQualityResponse result = service.snapshot();

        assertThat(result.supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(1, 0, null));
        assertThat(result.unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::panId)
                .containsExactly("catalog-pan");
    }

    @Test
    void 원천_확보는_공고목록_수집_대상_요청만_세고_최초_실패도_분모에_포함한다() {
        catalog("current-pan", OBSERVED_AT.minusSeconds(86400));
        catalog("missing-pan", OBSERVED_AT.minusSeconds(86400));
        catalog("ended-pan", OBSERVED_AT.minusSeconds(86400), "2026.01.01", "062", true);
        catalog("removed-pan", OBSERVED_AT.minusSeconds(86400), null, "062", false);
        catalog("daycare-pan", OBSERVED_AT.minusSeconds(86400), null, "064", true);
        checkpoint("current-pan", OBSERVED_AT.minusSeconds(600));
        checkpoint("ended-pan", OBSERVED_AT.minusSeconds(600));
        checkpoint("removed-pan", OBSERVED_AT.minusSeconds(600));

        LhAnnouncementQualityResponse result = service.snapshot();

        assertThat(result.supplyCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(2, 1, OBSERVED_AT.minusSeconds(600)));
        assertThat(result.detailCollection()).isEqualTo(
                new LhAnnouncementQualityResponse.CollectionCoverage(2, 0, null));
        assertThat(result.unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::sourceKey)
                .containsExactlyInAnyOrder(sourceKey("current-pan"), sourceKey("missing-pan"));
    }

    @Test
    void 최근_종료_공고의_원천_확보는_실제_수집_시각으로_확인한다() {
        catalog("recent-pan", OBSERVED_AT.minusSeconds(86400), "2026.09.27", "062", true);
        checkpoint("recent-pan", OBSERVED_AT.minusSeconds(43200));

        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();
    }

    @Test
    void 연결_기록의_조회_조건이_현재_목록과_다르면_연결_성공으로_세지_않는다() {
        catalog("changed-pan", OBSERVED_AT.minusSeconds(86400));
        progressStore.save(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, catalogKey("changed-pan"),
                new LhAnnouncementRequest("changed-pan", "03", "06", "07", "061").requestDescription(),
                "changed-pan");

        assertThat(service.snapshot().unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::panId)
                .containsExactly("changed-pan");
    }

    @Test
    void 목록이_변경돼도_기존_성공_원천_확보와_수집_시각은_보존한다() {
        catalog("stable-pan", OBSERVED_AT.minusSeconds(86400));
        checkpoint("stable-pan", OBSERVED_AT.minusSeconds(43200));

        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();

        jdbc.sql("UPDATE lh_announcement_catalog_entries SET changed_at = :changedAt")
                .param("changedAt", Timestamp.from(OBSERVED_AT.minusSeconds(60))).update();
        entityManager.clear();
        assertThat(service.snapshot().supplyCollection().collectedRequests()).isOne();
    }

    private void failure(String source, String request, String status, String errorType, String reason,
            long minutesAgo) {
        Timestamp occurredAt = Timestamp.from(OBSERVED_AT.minusSeconds(minutesAgo * 60));
        jdbc.sql("""
                INSERT INTO external_data_collection_failures
                    (source, request_description, occurred_at, attempt_count, status, last_occurred_at,
                     occurrence_count, recurrence_count, error_type, reason)
                VALUES (:source, :request, :occurredAt, 0, :status, :occurredAt, 1, 0, :errorType, :reason)
                """).param("source", source).param("request", request).param("occurredAt", occurredAt)
                .param("status", status).param("errorType", errorType).param("reason", reason).update();
    }

    private void catalog(String panId, Instant changedAt) {
        catalog(panId, changedAt, null, "062", true);
    }

    private void catalog(String panId, Instant changedAt, String closingDate, String supplyInfoTypeCode,
            boolean present) {
        var values = new HashMap<String, Object>();
        values.put("sourceKey", sourceKey(panId));
        values.put("panId", panId);
        values.put("supplyInfoTypeCode", supplyInfoTypeCode);
        values.put("closingDate", closingDate);
        values.put("present", present);
        values.put("record", fixtures.record("LH_ANNOUNCEMENT_CATALOG"));
        values.put("changedAt", Timestamp.from(changedAt));
        values.put("now", Timestamp.from(OBSERVED_AT));
        jdbc.sql("""
                INSERT INTO lh_announcement_catalog_entries
                    (source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
                     announcement_type_code, supply_info_type_code, closing_date, raw_payload,
                     changed_at, collected_at, present_in_latest_catalog, version, query_start_date, query_end_date, last_collection_record_id)
                VALUES (:sourceKey, :panId, '03', '06', '07', :supplyInfoTypeCode, :closingDate, '{}',
                        :changedAt, :now, :present, 0, '20260901', '20260928', :record)
                """).params(values).update();
    }

    private String sourceKey(String panId) {
        return "03:06:07:" + panId;
    }

    private String catalogKey(String panId) {
        return LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX + sourceKey(panId);
    }

    private void source(String pblancId, String panId, String endDate) {
        var values = new HashMap<String, Object>();
        values.put("pblancId", pblancId);
        values.put("houseSn", 1);
        values.put("suplyInsttNm", "LH");
        values.put("suplyTyNm", "국민임대");
        values.put("url", "https://apply.lh.or.kr/notice?panId=" + panId
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        values.put("endDe", endDate);
        var snapshot = JsonMapper.builder().build().convertValue(values,
                MyHomeAnnouncementSourceSnapshot.class);
        var row = MyHomeSourceViews.announcement(0, snapshot);
        row = MyHomeSourceViews.seen(row, "current", OBSERVED_AT);
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
