package com.toadzip.backend.ingest.quality.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(LhAnnouncementQualityStore.class)
class LhAnnouncementQualityStoreTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-28T09:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LhAnnouncementQualityStore store;

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
                                            lh_amount_preserved_reason)
                VALUES (:rowId, '전체', 1, 10000000, 0, 'LH_AMOUNT_NOT_PROVIDED')
                """).param("rowId", linkedRow).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_collection_checkpoints
                    (source, source_announcement_key, request_hash, request_description, pan_id, completed_at)
                VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'quality-test-announcement', :hash, 'quality-request',
                        'quality-pan', :completedAt)
                """).param("hash", "f".repeat(64))
                .param("completedAt", java.sql.Timestamp.from(OBSERVED_AT.minusSeconds(600))).update();
        jdbc.sql("""
                INSERT INTO external_data_collection_failures
                    (source, request_description, occurred_at, attempt_count, status, last_occurred_at,
                     occurrence_count, recurrence_count, error_type, reason)
                VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'quality-held-request', :occurredAt, 0, 'PENDING',
                        :occurredAt, 1, 0, 'IncompleteLhSupplyReplacementException', '공급행 감소')
                """).param("occurredAt", java.sql.Timestamp.from(OBSERVED_AT)).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_catalog_source
                    (source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
                     announcement_type_code, supply_info_type_code, content_fingerprint, raw_payload,
                     changed_at, collected_at, present_in_latest_catalog)
                VALUES ('03:06:063:quality-pan', 'quality-pan', '03', '06', '063', '01', :hash, '{}',
                        :changedAt, :changedAt, true)
                """).param("hash", "a".repeat(64))
                .param("changedAt", java.sql.Timestamp.from(OBSERVED_AT)).update();
        jdbc.sql("""
                INSERT INTO myhome_announcement_source
                    (source_key, pblanc_id, active, consecutive_miss_count, last_seen_run_id, collected_at)
                VALUES ('old-myhome-source', 'myhome-1', true, 0, 'old-run', :oldAt),
                       ('current-myhome-source', 'myhome-1', true, 0, 'current-run', :currentAt)
                """).param("oldAt", java.sql.Timestamp.from(OBSERVED_AT.minusSeconds(86400)))
                .param("currentAt", java.sql.Timestamp.from(OBSERVED_AT)).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_collection_links
                    (source, source_announcement_key, request_hash, request_description, pan_id, completed_at)
                VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'old-myhome-source', :hash, 'old-request',
                        'quality-pan', :completedAt)
                """).param("hash", "e".repeat(64))
                .param("completedAt", java.sql.Timestamp.from(OBSERVED_AT.minusSeconds(86400))).update();

        LhAnnouncementQualityResponse result = store.snapshot(OBSERVED_AT, OBSERVED_AT.minusSeconds(3600));

        assertThat(result.connection().total()).isEqualTo(2);
        assertThat(result.connection().housingTypeLinked()).isZero();
        assertThat(result.connection().unlinkedReasons()).containsEntry("주택형 후보 모호", 1L);
        assertThat(result.amounts()).isEqualTo(new LhAnnouncementQualityResponse.Coverage(2, 1));
        assertThat(result.schedules().reviewed()).isOne();
        assertThat(result.supplyCollection().freshRequests()).isOne();
        assertThat(result.unlinkedLhLeaseCatalogCount()).isOne();
        assertThat(result.unlinkedLhCandidates())
                .extracting(LhAnnouncementQualityResponse.UnlinkedLhCandidate::panId)
                .containsExactly("quality-pan");
        assertThat(result.preservedSourceRequestCount()).isOne();
        assertThat(result.preservedAmountTargetCount()).isOne();
        assertThat(result.preservedAmountReasons()).containsEntry("LH_AMOUNT_NOT_PROVIDED", 1L);
        assertThat(result.heldRequests()).extracting(LhAnnouncementQualityResponse.HeldRequest::requestDescription)
                .contains("quality-held-request");

        jdbc.sql("""
                INSERT INTO lh_announcement_collection_links
                    (source, source_announcement_key, request_hash, request_description, pan_id, completed_at)
                VALUES ('LH_ANNOUNCEMENT_SUPPLY', 'current-myhome-source', :hash, 'current-request',
                        'quality-pan', :completedAt)
                """).param("hash", "d".repeat(64))
                .param("completedAt", java.sql.Timestamp.from(OBSERVED_AT)).update();
        assertThat(store.snapshot(OBSERVED_AT, OBSERVED_AT.minusSeconds(3600))
                .unlinkedLhLeaseCatalogCount()).isZero();
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
