package com.toadzip.backend.ingest.failure.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IngestFailureReviewControllerIntegrationTest {

    private static final String ENDPOINT = "/api/admin/ingest/failure-reviews";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @ParameterizedTest
    @CsvSource({"complex, 3:123-1:-1:-1:-1:-1:", "announcement, 6:notice1:1"})
    void 실패_대상_이름은_현재_원천을_우선한다(String domain, String key) throws Exception {
        UUID record = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_collection_records
                    (id, version, source, started_at, finished_at, status, stored_row_count)
                VALUES (:record, 0, :source, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SUCCESS', 1)
                """).param("record", record).param("source", "MYHOME_" + domain.toUpperCase()).update();
        if (domain.equals("complex")) {
            long region = jdbc.sql("""
                    INSERT INTO myhome_complex_source_regions
                        (version, province_code, district_code, collected_at, last_collection_record_id)
                    VALUES (0, '11', '110', CURRENT_TIMESTAMP, :record) RETURNING id
                    """).param("record", record).query(Long.class).single();
            long bundle = jdbc.sql("""
                    INSERT INTO myhome_complex_source_bundles (version, hsmp_sn, region_id)
                    VALUES (0, 123, :region) RETURNING id
                    """).param("region", region).query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO myhome_complex_source_rows (source_id, source_order, hsmp_sn, hsmp_nm, collected_at)
                    VALUES (:bundle, 0, 123, '최신 원천명', CURRENT_TIMESTAMP)
                    """).param("bundle", bundle).update();
            stageFailure("myhome_complex_mapping_failures", key, "source_complex_identifier", "123",
                    "GEOCODING_ERROR");
        }
        if (domain.equals("announcement")) {
            long bundle = jdbc.sql("""
                    INSERT INTO myhome_announcement_source_bundles (version, pblanc_id, last_collection_record_id)
                    VALUES (0, 'notice', :record) RETURNING id
                    """).param("record", record).query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO myhome_announcement_source_rows
                        (source_id, collection_record_id, request_supply_type_code, collected_at, source_order,
                         pblanc_id, house_sn, pblanc_nm, active, consecutive_miss_count)
                    VALUES (:bundle, :record, '01', CURRENT_TIMESTAMP, 0, 'notice', 1, '과거 비활성명', false, 2),
                           (:bundle, :record, '02', '2026-10-01T00:00:00Z', 0, 'notice', 1, '최신 원천명', true, 0)
                    """).param("bundle", bundle).param("record", record).update();
            jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_id = :identifier")
                    .param("identifier", "\tnotice\u3000").update();
            stageFailure("myhome_announcement_mapping_failures", key, "source_announcement_identifier", "notice",
                    "COMPLEX_NOT_FOUND");
        }
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", domain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].targetName").value("최신 원천명"))
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value("NOT_FOUND"));
    }

    @Test
    void 실패가_없는_페이지에도_정확한_전체_건수를_반환한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 다섯_실패_종류를_도메인별로_나누고_범위를_벗어난_페이지에도_전체_건수를_보존한다() throws Exception {
        collectionFailure("MYHOME_COMPLEX", "PENDING");
        collectionFailure("LH_LEASE_CATALOG", "SKIPPED");
        collectionFailure("MYHOME_ANNOUNCEMENT", "PENDING");
        collectionFailure("LH_ANNOUNCEMENT_CATALOG", "PENDING");
        collectionFailure("LH_ANNOUNCEMENT_DETAIL", "RESOLVED");
        collectionFailure("LH_ANNOUNCEMENT_SUPPLY", "PENDING");
        stageFailure("myhome_complex_mapping_failures", "complex", "source_complex_identifier", "complex",
                "GEOCODING_ERROR");
        stageFailure("lh_household_enrichment_failures", "household", "complex_name", "단지명",
                "COMPLEX_NOT_FOUND");
        stageFailure("myhome_announcement_mapping_failures", "announcement", "source_announcement_identifier",
                "announcement", "COMPLEX_NOT_FOUND");
        stageFailure("lh_announcement_enrichment_failures", "enrichment", "source_announcement_identifier",
                "enrichment", "ANNOUNCEMENT_NOT_FOUND");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("status", "ALL").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "announcement").param("status", "ALL").param("page", "8")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "announcement").param("category", "collection").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].source").value("LH_ANNOUNCEMENT_DETAIL"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "household"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 수집_요청의_과거_미해결_중복이_남아도_최신_해결_상태를_한_건으로_조회한다() throws Exception {
        collectionFailure("MYHOME_COMPLEX", "PENDING");
        collectionFailure("MYHOME_COMPLEX", "RESOLVED");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "collection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "collection").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "collection").param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 단지_실패는_원천_연결의_대표_제품을_직접_일치하는_제품보다_우선한다() throws Exception {
        complex("member", "원천 단지", false);
        long representative = complex("representative", "대표 단지", false);
        jdbc.sql("INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id) "
                        + "VALUES ('member', :id)")
                .param("id", representative).update();
        stageFailure("myhome_complex_mapping_failures", "complex-key", "source_complex_identifier", "member",
                "GEOCODING_ERROR");
        jdbc.sql("INSERT INTO housing_types (housing_complex_id, name, exclusive_area) VALUES (:id, '36형', 36)")
                .param("id", representative).update();

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value("EXISTS"))
                .andExpect(jsonPath("$.data.items[0].product.id").value(representative))
                .andExpect(jsonPath("$.data.items[0].product.name").value("대표 단지"))
                .andExpect(jsonPath("$.data.items[0].product.resourceType").value("complexes"))
                .andExpect(jsonPath("$.data.items[0].product.housingTypeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(true));
    }

    @Test
    void 대표_연결이_없으면_단지_식별자_정확_일치로_휴지통_제품까지_연결한다() throws Exception {
        long id = complex("exact", "휴지통 단지", true);
        stageFailure("myhome_complex_mapping_failures", "key", "source_complex_identifier", "exact",
                "GEOCODING_ERROR");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.id").value(id))
                .andExpect(jsonPath("$.data.items[0].product.deleted").value(true))
                .andExpect(jsonPath("$.data.items[0].product.hasCoordinates").value(true))
                .andExpect(jsonPath("$.data.items[0].product.publicDetailAvailable").value(false))
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(false))
                .andExpect(jsonPath("$.data.items[0].product.listExclusionReasons[0]").value("ADMIN_DELETED"));
    }

    @Test
    void 좌표가_없는_단지는_상세_조회와_목록_노출_조건을_구분한다() throws Exception {
        long id = complex("exact", "좌표 없는 단지", false);
        jdbc.sql("ALTER TABLE housing_complexes ALTER COLUMN latitude DROP NOT NULL").update();
        jdbc.sql("UPDATE housing_complexes SET latitude = NULL WHERE id = :id").param("id", id).update();
        stageFailure("myhome_complex_mapping_failures", "key", "source_complex_identifier", "exact",
                "GEOCODING_ERROR");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.hasCoordinates").value(false))
                .andExpect(jsonPath("$.data.items[0].product.publicDetailAvailable").value(true))
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(false))
                .andExpect(jsonPath("$.data.items[0].product.listExclusionReasons[0]").value("MISSING_COORDINATES"));
    }

    @ParameterizedTest
    @CsvSource(value = {"' ',UNKNOWN", "' exact ',NOT_FOUND"})
    void 공백_식별자를_추정하거나_정규화하여_제품에_연결하지_않는다(String identifier, String linkStatus)
            throws Exception {
        complex("exact", "직접 일치 단지", false);
        stageFailure("myhome_complex_mapping_failures", "key", "source_complex_identifier", identifier,
                "GEOCODING_ERROR");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value(linkStatus))
                .andExpect(jsonPath("$.data.items[0].product").isEmpty());
    }

    @Test
    void 제품_부재와_추정할_수_없는_연결을_구분하고_수집_해결_시각과_실행을_보존한다() throws Exception {
        stageFailure("myhome_complex_mapping_failures", "missing", "source_complex_identifier", "missing",
                "GEOCODING_ERROR");
        stageFailure("lh_household_enrichment_failures", "household", "complex_name", "같은 이름 단지",
                "COMPLEX_NOT_FOUND");
        complex("other", "같은 이름 단지", false);
        collectionFailure("MYHOME_COMPLEX", "RESOLVED");
        UUID resolvedExecutionId = UUID.randomUUID();
        jdbc.sql("UPDATE external_data_collection_failures SET resolved_at = '2026-10-02T02:00:00Z', "
                        + "resolved_execution_id = :id").param("id", resolvedExecutionId).update();

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "complex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.items[0].product").isEmpty());
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "household"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].product").isEmpty());
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param("category", "collection").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productLinkStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].lastResolvedAt").value("2026-10-02T02:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].lastResolvedExecutionId").value(resolvedExecutionId.toString()));
    }

    @ParameterizedTest
    @CsvSource({"ORIGINAL,true", "원공고,true", "CORRECTION,false", "정정공고,false", "CANCELLATION,false"})
    void 공고_목록은_원공고와_이전_공고에_연결된_정정공고만_허용한다(String publicationType, boolean eligible)
            throws Exception {
        announcement("notice", publicationType, null);
        stageFailure("myhome_announcement_mapping_failures", "key", "source_announcement_identifier", "notice",
                "COMPLEX_NOT_FOUND");
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.publicDetailAvailable").value(true))
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(eligible));
    }

    @Test
    void 공고_공급행_수를_단지수와_구분하고_마감된_공고도_목록_대상이_된다() throws Exception {
        long notice = announcement("notice", "ORIGINAL", null);
        long complexId = complex("complex", "연결 단지", false);
        jdbc.sql("""
                INSERT INTO supply_rows (announcement_id, housing_complex_id, source_supply_row_identifier,
                    display_order, source_complex_name, source_housing_type_name, supply_pnu, supply_category,
                    lh_total_supply_household_count_enriched, lh_total_supply_household_count_owned)
                VALUES (:notice, :complex, 'row-1', 1, '단지', '36형', 'pnu', 'NEW_SUPPLY', FALSE, FALSE),
                       (:notice, :complex, 'row-2', 2, '단지', '46형', 'pnu', 'NEW_SUPPLY', FALSE, FALSE),
                       (:notice, NULL, 'row-3', 3, '미연결 단지', '56형', 'pnu', 'NEW_SUPPLY', FALSE, FALSE)
                """).param("notice", notice).param("complex", complexId).update();
        jdbc.sql("""
                INSERT INTO announcement_application_schedules (announcement_id, state, start_date, end_date,
                    source_url, source_page)
                VALUES (:notice, 'CONFIRMED', '2026-01-01', '2026-01-02', 'https://official.test', 1)
                """).param("notice", notice).update();
        jdbc.sql("""
                INSERT INTO announcement_attachments (announcement_id, display_order, file_name, file_type, file_url)
                VALUES (:notice, 1, '공고문', 'ANNOUNCEMENT', 'https://official.test/notice.pdf')
                """).param("notice", notice).update();
        stageFailure("lh_announcement_enrichment_failures", "key", "source_announcement_identifier", "notice",
                "ANNOUNCEMENT_NOT_FOUND");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].source").value("LH_ANNOUNCEMENT"))
                .andExpect(jsonPath("$.data.items[0].product.linkedComplexCount").value(1))
                .andExpect(jsonPath("$.data.items[0].product.supplyRowCount").value(3))
                .andExpect(jsonPath("$.data.items[0].product.applicationScheduleCount").value(1))
                .andExpect(jsonPath("$.data.items[0].product.attachmentCount").value(1))
                .andExpect(jsonPath("$.data.items[0].product.applicationStartDate").value("2026-01-01"))
                .andExpect(jsonPath("$.data.items[0].product.applicationScheduleReviewed").value(false))
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(true))
                .andExpect(jsonPath("$.data.items[0].raw").doesNotExist());
    }

    @Test
    void 삭제되지_않은_취소_후속공고는_이전_공고를_목록에서_제외한다() throws Exception {
        long original = announcement("original", "ORIGINAL", null);
        long successor = announcement("successor", "CANCELLATION", original);
        stageFailure("myhome_announcement_mapping_failures", "key", "source_announcement_identifier", "original",
                "COMPLEX_NOT_FOUND");
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(false))
                .andExpect(jsonPath("$.data.items[0].product.listExclusionReasons[0]").value("HAS_SUCCESSOR"));
        jdbc.sql("UPDATE announcements SET admin_deleted = TRUE WHERE id = :id").param("id", successor).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(true));
    }

    @ParameterizedTest
    @CsvSource({"CORRECTION", "정정공고"})
    void 이전_공고에_연결된_정정공고는_목록_대상이다(String publicationType) throws Exception {
        long previous = announcement("previous", "ORIGINAL", null);
        announcement("correction", publicationType, previous);
        stageFailure("myhome_announcement_mapping_failures", "key", "source_announcement_identifier", "correction",
                "COMPLEX_NOT_FOUND");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("domain", "announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].product.publicListEligible").value(true))
                .andExpect(jsonPath("$.data.items[0].product.listExclusionReasons").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"domain,wrong", "category,wrong", "status,SKIPPED", "page,-1", "size,0", "size,101"})
    void 잘못된_조회_조건을_거부한다(String field, String value) throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("domain", "complex").param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 관리자_권한이_없으면_실패와_제품_정보를_조회할_수_없다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("user").roles("USER")).param("domain", "complex"))
                .andExpect(status().isForbidden());
    }

    private void collectionFailure(String source, String status) {
        jdbc.sql("""
                INSERT INTO external_data_collection_failures (source, request_description, status, attempt_count,
                    error_type, reason, occurred_at, last_occurred_at, occurrence_count, recurrence_count)
                VALUES (:source, :source, :status, 3, 'API_ERROR', '수집 실패',
                    '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z', 2, 1)
                """).param("source", source).param("status", status).update();
    }

    private void stageFailure(String table, String key, String identifierColumn, String identifier, String reason) {
        jdbc.sql("""
                INSERT INTO %s (source_key, %s, reason, detail, status, occurred_at, last_occurred_at,
                    occurrence_count, recurrence_count)
                VALUES (:key, :identifier, :reason, '상세 실패', 'PENDING',
                    '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z', 2, 1)
                """.formatted(table, identifierColumn))
                .param("key", key).param("identifier", identifier).param("reason", reason).update();
    }

    private long complex(String identifier, String name, boolean deleted) {
        return jdbc.sql("""
                INSERT INTO housing_complexes (source_complex_identifier, name, admin_deleted, supply_type, provider,
                    road_address, pnu, legal_dong_code, province_code, city_county_district_code, latitude, longitude,
                    total_household_count, parking_space_count)
                VALUES (:identifier, :name, :deleted, '행복주택', 'LH', '서울', 'pnu', '11110', '11', '110',
                    37.5, 127.0, 10, 10) RETURNING id
                """).param("identifier", identifier).param("name", name).param("deleted", deleted)
                .query(Long.class).single();
    }

    private long announcement(String identifier, String status, Long previousId) {
        return jdbc.sql("""
                INSERT INTO announcements (source_announcement_identifier, name, status, previous_announcement_id,
                    supply_type, recruitment_type, provider, posted_date, application_start_date, application_end_date,
                    winner_announcement_date, original_url, application_schedule_reviewed, lh_pan_id_reviewed,
                    lh_reception_place_owned, view_count)
                VALUES (:identifier, '모집 공고', :status, :previous, 'HAPPY_HOUSING', 'NEW', 'LH',
                    '2026-01-01', '2026-01-01', '2026-01-02', '2026-01-03', 'https://official.test',
                    FALSE, FALSE, FALSE, 0) RETURNING id
                """).param("identifier", identifier).param("status", status).param("previous", previousId)
                .query(Long.class).single();
    }
}
