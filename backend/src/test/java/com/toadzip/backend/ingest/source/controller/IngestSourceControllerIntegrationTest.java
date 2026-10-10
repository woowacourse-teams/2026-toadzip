package com.toadzip.backend.ingest.source.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.main.web-application-type=servlet",
        "ingest.base-url.myhome-complex=https://user:password@example.test/complex?serviceKey=secret#fragment",
        "ingest.base-url.myhome-announcement=https://example.test/announcement?serviceKey=secret",
        "ingest.base-url.lh=https://example.test/lh?serviceKey=secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IngestSourceControllerIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.toadzip.backend.user.repository.UserRepository privacyPermissionUsers;

    private String privacyPermissionMemberId() {
        var member = com.toadzip.backend.user.domain.User.create(
                "google:permission-" + java.util.UUID.randomUUID(), java.time.LocalDateTime.now());
        return privacyPermissionUsers.saveAndFlush(member).getId().toString();
    }


    private static final String ENDPOINT = "/api/admin/ingest/sources";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void storeSources() {
        storeShSource();
        for (String category : java.util.List.of("MYHOME_COMPLEX", "MYHOME_ANNOUNCEMENT", "LH_LEASE_CATALOG",
                "LH_ANNOUNCEMENT_CATALOG", "LH_ANNOUNCEMENT_DETAIL", "LH_ANNOUNCEMENT_SUPPLY")) {
            storeCurrentSource(category);
        }
        jdbc.sql("UPDATE myhome_complex_source_rows SET hsmp_nm = '두꺼비 단지'").update();
        jdbc.sql("UPDATE lh_lease_catalog_source_rows SET complex_label = '두꺼비 임대'").update();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_nm = '두꺼비 공고', "
                + "pc_url = 'https://official.test/myhome'").update();
        jdbc.sql("UPDATE lh_announcement_detail_rows SET dataset_type = 'COMPLEX', complex_name = '두꺼비 상세', "
                + "url = 'https://official.test/attachment.pdf'").update();
        jdbc.sql("UPDATE lh_announcement_supply_rows SET complex_label = '두꺼비 공급'").update();
        jdbc.sql("UPDATE lh_announcement_catalog_entries SET changed_at = '2026-10-01T01:00:00Z', raw_payload = "
                + "'{\"PAN_NM\":\"두꺼비 LH 공고\",\"DTL_URL\":\"https://official.test/lh\",\"EXTRA_FIELD\":\"보존값\"}'").update();
        for (String table : java.util.List.of("myhome_complex_source_rows", "myhome_announcement_source_rows",
                "lh_lease_catalog_source_rows", "lh_announcement_catalog_entries", "lh_announcement_detail_rows",
                "lh_announcement_supply_rows")) {
            jdbc.sql("UPDATE " + table + " SET collected_at = '2026-10-02T01:00:00Z'").update();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "MYHOME_COMPLEX, 신규 단지, hsmp_nm, 3:123-1:-1:-1:-1:-1:",
            "LH_LEASE_CATALOG, 신규 임대, complex_label,",
            "MYHOME_ANNOUNCEMENT, 신규 공고, pblanc_nm, 10:MYHOME-1231:1",
            "LH_ANNOUNCEMENT_CATALOG, 신규 LH 공고, PAN_NM, lh-key",
            "LH_ANNOUNCEMENT_DETAIL, 신규 상세, complex_name,",
            "LH_ANNOUNCEMENT_SUPPLY, 신규 공급, complex_label,"
    })
    void 새_수집_원천의_필드와_식별자를_조회한다(
            String category, String name, String rawField, String key
    ) throws Exception {
        updateCurrentSource(category, name);
        var result = mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value(name))
                .andExpect(jsonPath("$.data.items[0].raw." + rawField).value(name))
                .andExpect(jsonPath("$.data.items[0].collectedAt").value("2026-10-04T01:00:00Z"));
        if (key != null) result.andExpect(jsonPath("$.data.items[0].sourceKey").value(key));
        if (category.equals("LH_ANNOUNCEMENT_DETAIL") || category.equals("LH_ANNOUNCEMENT_SUPPLY")) {
            result.andExpect(jsonPath("$.data.items[0].raw.pan_id").value("LH-123"))
                    .andExpect(jsonPath("$.data.items[0].raw.request_hash").isString());
        }
    }

    @Test
    void 원천키는_기존_정제와_같이_탭과_유니코드_공백을_제거하고_raw는_보존한다() throws Exception {
        String rawIdentifier = "\tMYHOME-123\u3000";
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_id = :identifier")
                .param("identifier", rawIdentifier).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_ANNOUNCEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sourceKey").value("10:MYHOME-1231:1"))
                .andExpect(jsonPath("$.data.items[0].raw.pblanc_id").value(rawIdentifier));
    }

    @Test
    void 빈_지역_수집은_원천_목록을_비운다() throws Exception {
        jdbc.sql("DELETE FROM myhome_complex_source_rows").update();
        jdbc.sql("DELETE FROM myhome_complex_source_bundles").update();
        assertEmptyCurrent("MYHOME_COMPLEX");
    }

    @ParameterizedTest
    @CsvSource({"LH_LEASE_CATALOG, lh_lease_catalog_source_rows", "LH_ANNOUNCEMENT_DETAIL, lh_announcement_detail_rows",
            "LH_ANNOUNCEMENT_SUPPLY, lh_announcement_supply_rows"})
    void 빈_수집_묶음의_원천은_빈_목록으로_조회한다(String category, String table) throws Exception {
        jdbc.sql("DELETE FROM " + table).update();
        assertEmptyCurrent(category);
    }

    private void assertEmptyCurrent(String category) throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", category))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private void updateCurrentSource(String category, String name) {
        String table = switch (category) {
            case "MYHOME_COMPLEX" -> "myhome_complex_source_rows";
            case "MYHOME_ANNOUNCEMENT" -> "myhome_announcement_source_rows";
            case "LH_LEASE_CATALOG" -> "lh_lease_catalog_source_rows";
            case "LH_ANNOUNCEMENT_DETAIL" -> "lh_announcement_detail_rows";
            case "LH_ANNOUNCEMENT_SUPPLY" -> "lh_announcement_supply_rows";
            default -> "lh_announcement_catalog_entries";
        };
        String field = switch (category) {
            case "MYHOME_COMPLEX" -> "hsmp_nm";
            case "MYHOME_ANNOUNCEMENT" -> "pblanc_nm";
            case "LH_ANNOUNCEMENT_DETAIL" -> "complex_name";
            default -> "complex_label";
        };
        if (category.equals("LH_ANNOUNCEMENT_CATALOG")) {
            jdbc.sql("UPDATE " + table + " SET raw_payload = :raw, collected_at = '2026-10-04T01:00:00Z'")
                    .param("raw", "{\"PAN_NM\":\"" + name + "\"}").update();
            return;
        }
        jdbc.sql("UPDATE " + table + " SET " + field + " = :name, collected_at = '2026-10-04T01:00:00Z'")
                .param("name", name).update();
    }

    private void storeShSource() {
        jdbc.sql("""
                INSERT INTO sh_announcement_source (source_key,seq,title,department,registered_date,
                original_url,list_url,raw_list_html,raw_detail_html,collected_at)
                VALUES ('SH:m_247:100','100','두꺼비 SH 공고','공급부','2026-10-02',
                'https://www.i-sh.co.kr/app/lay2/program/S48T561C563/www/brd/m_247/view.do?seq=100&multi_itm_seq=2',
                'https://www.i-sh.co.kr/app/lay2/program/S48T561C563/www/brd/m_247/list.do?multi_itm_seq=2',
                '<html>목록</html>','<html>상세</html>',:collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
    }

    private void storeCurrentSource(String category) {
        UUID record = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_collection_records
                    (id, version, source, started_at, finished_at, status, stored_row_count)
                VALUES (:id, 0, :source, '2026-10-04T01:00:00Z', '2026-10-04T01:00:00Z', 'SUCCESS', 1)
                """).param("id", record).param("source", category).update();
        switch (category) {
            case "MYHOME_COMPLEX" -> {
                long region = jdbc.sql("""
                        INSERT INTO myhome_complex_source_regions
                            (version, province_code, district_code, collected_at, last_collection_record_id)
                        VALUES (0, '11', '110', '2026-10-04T01:00:00Z', :record) RETURNING id
                        """).param("record", record).query(Long.class).single();
                long bundle = jdbc.sql("""
                        INSERT INTO myhome_complex_source_bundles (version, hsmp_sn, region_id)
                        VALUES (0, 123, :region) RETURNING id
                        """).param("region", region).query(Long.class).single();
                jdbc.sql("""
                        INSERT INTO myhome_complex_source_rows (source_id, source_order, hsmp_sn, hsmp_nm, collected_at)
                        VALUES (:bundle, 0, 123, '신규 단지', '2026-10-04T01:00:00Z')
                        """).param("bundle", bundle).update();
            }
            case "MYHOME_ANNOUNCEMENT" -> {
                long bundle = jdbc.sql("""
                        INSERT INTO myhome_announcement_source_bundles (version, pblanc_id, last_collection_record_id)
                        VALUES (0, 'MYHOME-123', :record) RETURNING id
                        """).param("record", record).query(Long.class).single();
                jdbc.sql("""
                        INSERT INTO myhome_announcement_source_rows
                            (source_id, collection_record_id, request_supply_type_code, collected_at, source_order,
                             pblanc_id, house_sn, pblanc_nm, active, consecutive_miss_count)
                        VALUES (:bundle, :record, '01', '2026-10-04T01:00:00Z', 0, 'MYHOME-123', 1, '신규 공고', false, 2)
                        """).param("bundle", bundle).param("record", record).update();
            }
            case "LH_LEASE_CATALOG" -> {
                long bundle = jdbc.sql("""
                        INSERT INTO lh_lease_catalog_source_bundles
                            (version, scope_key, collected_at, last_collection_record_id)
                        VALUES (0, 'ALL', '2026-10-04T01:00:00Z', :record) RETURNING id
                        """).param("record", record).query(Long.class).single();
                jdbc.sql("""
                        INSERT INTO lh_lease_catalog_source_rows (source_id, source_order, complex_label, collected_at)
                        VALUES (:bundle, 0, '신규 임대', '2026-10-04T01:00:00Z')
                        """).param("bundle", bundle).update();
            }
            case "LH_ANNOUNCEMENT_CATALOG" -> jdbc.sql("""
                    INSERT INTO lh_announcement_catalog_entries
                        (version, source_key, raw_payload, query_start_date, query_end_date, changed_at, collected_at,
                         present_in_latest_catalog, last_collection_record_id)
                    VALUES (0, 'lh-key', '{"PAN_NM":"신규 LH 공고"}', '20261001', '20261004',
                            '2026-10-03T01:00:00Z', '2026-10-04T01:00:00Z', false, :record)
                    """).param("record", record).update();
            default -> {
                String table = "lh_announcement_supply";
                String field = "complex_label";
                String name = "신규 공급";
                if (category.equals("LH_ANNOUNCEMENT_DETAIL")) {
                    table = "lh_announcement_detail";
                    field = "complex_name";
                    name = "신규 상세";
                }
                String requestHash = LhAnnouncementQuery.requestHashOf("fixture");
                long bundle = jdbc.sql("""
                        INSERT INTO lh_announcement_query_sources
                            (version, source, pan_id, query_hash, request_hash, request_description, collected_at,
                             verified_empty, last_collection_record_id)
                        VALUES (0, :source, 'LH-123', :hash, :hash, 'fixture',
                                '2026-10-04T01:00:00Z', false, :record) RETURNING id
                        """).param("source", category).param("record", record)
                        .param("hash", requestHash).query(Long.class).single();
                jdbc.sql("INSERT INTO " + table + "_rows (source_id, source_order, " + field + ", collected_at)"
                                + " VALUES (:bundle, 0, :name, '2026-10-04T01:00:00Z')")
                        .param("bundle", bundle).param("name", name).update();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "MYHOME_COMPLEX, 두꺼비 단지, hsmp_nm, https://example.test/complex/rentalHouseGwList",
            "LH_LEASE_CATALOG, 두꺼비 임대, complex_label, https://example.test/lh/lhLeaseInfo1/lhLeaseInfo1",
            "MYHOME_ANNOUNCEMENT, 두꺼비 공고, pblanc_nm, https://example.test/announcement/rsdtRcritNtcList",
            "LH_ANNOUNCEMENT_CATALOG, 두꺼비 LH 공고, PAN_NM, https://example.test/lh/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1",
            "LH_ANNOUNCEMENT_DETAIL, 두꺼비 상세, complex_name, "
                    + "https://example.test/lh/lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1",
            "LH_ANNOUNCEMENT_SUPPLY, 두꺼비 공급, complex_label, "
                    + "https://example.test/lh/lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1",
            "SH_ANNOUNCEMENT, 두꺼비 SH 공고, title, "
                    + "https://www.i-sh.co.kr/app/lay2/program/S48T561C563/www/brd/m_247/list.do?multi_itm_seq=2"
    })
    void 관리자는_저장된_원천을_출처_URL과_함께_조회한다(
            String category, String name, String rawField, String sourceUrl
    ) throws Exception {
        MvcResult result = mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").isNumber())
                .andExpect(jsonPath("$.data.items[0].sourceKey").isString())
                .andExpect(jsonPath("$.data.items[0].name").value(name))
                .andExpect(jsonPath("$.data.items[0].sourceUrl").value(sourceUrl))
                .andExpect(jsonPath("$.data.items[0].raw." + rawField).value(name))
                .andExpect(jsonPath("$.data.items[0].collectedAt").value("2026-10-02T01:00:00Z"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("serviceKey", "secret", "password");
    }

    @Test
    void SH_원천은_목록과_상세_HTML을_그대로_반환하고_추출_결과를_포함하지_않는다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("category", "SH_ANNOUNCEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sourceKey").value("SH:m_247:100"))
                .andExpect(jsonPath("$.data.items[0].sourceUpdatedAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].raw.raw_list_html").value("<html>목록</html>"))
                .andExpect(jsonPath("$.data.items[0].raw.raw_detail_html").value("<html>상세</html>"))
                .andExpect(jsonPath("$.data.items[0].raw.body_text").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].raw.attachments").doesNotExist());
    }

    @Test
    void 공고는_저장된_공식_원문_URL과_원천_변경_시각을_보존한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "LH_ANNOUNCEMENT_CATALOG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].originalUrl").value("https://official.test/lh"))
                .andExpect(jsonPath("$.data.items[0].sourceUpdatedAt").value("2026-10-01T01:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].raw.EXTRA_FIELD").value("보존값"));
    }

    @Test
    void 마이홈_원문_URL은_기존_공고_정제의_URL_선택_순서와_같다() throws Exception {
        jdbc.sql("UPDATE myhome_announcement_source_rows SET url = 'https://official.test/preferred'").update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_ANNOUNCEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].originalUrl").value("https://official.test/preferred"));
    }

    @Test
    void 상세_첨부파일_URL은_공고_원문_URL로_표시하지_않는다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "LH_ANNOUNCEMENT_DETAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].originalUrl").isEmpty())
                .andExpect(jsonPath("$.data.items[0].raw.url").value("https://official.test/attachment.pdf"));
    }

    @Test
    void 원문이_없는_단지는_URL을_추정하지_않는다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].originalUrl").isEmpty())
                .andExpect(jsonPath("$.data.items[0].sourceUpdatedAt").isEmpty());
    }

    @Test
    void 이름으로_검색하고_최신_수집순으로_안정된_페이지를_조회한다() throws Exception {
        jdbc.sql("""
                INSERT INTO myhome_complex_source_rows (source_id, source_order, hsmp_sn, hsmp_nm, collected_at)
                SELECT id, 1, 124, '두꺼비 신규', CAST(:collectedAt AS timestamptz) FROM myhome_complex_source_bundles
                UNION ALL SELECT id, 2, 125, '두꺼비 최신', CAST(:collectedAt AS timestamptz) FROM myhome_complex_source_bundles
                UNION ALL SELECT id, 3, 126, '다른 단지', CAST(:collectedAt AS timestamptz) FROM myhome_complex_source_bundles
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-03T01:00:00Z"))).update();

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_COMPLEX").param("keyword", " 두꺼비 ").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("두꺼비 최신"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_COMPLEX").param("keyword", "두꺼비")
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sourceKey").value("3:123-1:-1:-1:-1:-1:"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 식별자_검색은_SQL_와일드카드를_문자로_취급한다() throws Exception {
        jdbc.sql("""
                INSERT INTO lh_announcement_catalog_entries(version, source_key, raw_payload, query_start_date, query_end_date, changed_at, collected_at, present_in_latest_catalog, last_collection_record_id) SELECT 0, 'complex%_key', '{\"PAN_NM\":\"특수문자 단지\"}', '20261001', '20261004', now(), now(), true, last_collection_record_id FROM lh_announcement_catalog_entries LIMIT 1
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "LH_ANNOUNCEMENT_CATALOG").param("keyword", "%_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceKey").value("complex%_key"));
    }

    @Test
    void 마지막_페이지_뒤는_빈_목록과_전체_개수를_반환한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_COMPLEX").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 자바스크립트_안전_정수_범위_밖의_원천_식별자와_금액은_문자열로_보존한다() throws Exception {
        jdbc.sql("""
                UPDATE myhome_complex_source_rows SET hsmp_sn = 9007199254740993, bass_rent_gtn = 9007199254740993
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raw.hsmp_sn").isString())
                .andExpect(jsonPath("$.data.items[0].raw.hsmp_sn").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].raw.bass_rent_gtn").isString())
                .andExpect(jsonPath("$.data.items[0].raw.bass_rent_gtn").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].id").isNumber());
    }

    @Test
    void LH_중첩_객체와_배열의_큰_정수도_문자열로_보존한다() throws Exception {
        jdbc.sql("""
                UPDATE lh_announcement_catalog_entries SET raw_payload =
                    '{"PAN_NM":"두꺼비 LH 공고","nested":{"identifier":9007199254740993},
                      "values":[-9007199254740993,{"identifier":123456789012345678901234567890}]}'
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "LH_ANNOUNCEMENT_CATALOG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raw.nested.identifier").isString())
                .andExpect(jsonPath("$.data.items[0].raw.nested.identifier").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].raw.values[0]").isString())
                .andExpect(jsonPath("$.data.items[0].raw.values[0]").value("-9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].raw.values[1].identifier").isString())
                .andExpect(jsonPath("$.data.items[0].raw.values[1].identifier")
                        .value("123456789012345678901234567890"));
    }

    @Test
    void 안전_범위의_원천_정수와_페이지_개수는_숫자로_유지한다() throws Exception {
        jdbc.sql("""
                UPDATE myhome_complex_source_rows
                SET hshld_co = 0, bass_rent_gtn = 9007199254740991, bass_mt_rntchrg = -9007199254740991
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raw.hsmp_sn").isNumber())
                .andExpect(jsonPath("$.data.items[0].raw.hsmp_sn").value(123))
                .andExpect(jsonPath("$.data.items[0].raw.hshld_co").isNumber())
                .andExpect(jsonPath("$.data.items[0].raw.hshld_co").value(0))
                .andExpect(jsonPath("$.data.items[0].raw.bass_rent_gtn").isNumber())
                .andExpect(jsonPath("$.data.items[0].raw.bass_rent_gtn").value(9007199254740991L))
                .andExpect(jsonPath("$.data.items[0].raw.bass_mt_rntchrg").isNumber())
                .andExpect(jsonPath("$.data.items[0].raw.bass_mt_rntchrg").value(-9007199254740991L))
                .andExpect(jsonPath("$.data.page").isNumber())
                .andExpect(jsonPath("$.data.totalElements").isNumber());
    }

    @Test
    void 원천_소수값은_브라우저_반올림_없이_문자열로_전달한다() throws Exception {
        jdbc.sql("""
                UPDATE lh_announcement_catalog_entries SET raw_payload =
                    '{"PAN_NM":"두꺼비 LH 공고","amount":0.1234567890123456789}'
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "LH_ANNOUNCEMENT_CATALOG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raw.amount").isString())
                .andExpect(jsonPath("$.data.items[0].raw.amount").value("0.1234567890123456789"));
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN, 0, 20", "MYHOME_COMPLEX, -1, 20", "MYHOME_COMPLEX, 0, 0",
            "MYHOME_COMPLEX, 0, 101"})
    void 잘못된_분류나_페이지_범위를_거부한다(String category, String page, String size) throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", category).param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 비로그인_사용자는_원천을_조회할_수_없다() throws Exception {
        mockMvc.perform(get(ENDPOINT).param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_사용자는_원천을_조회할_수_없다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user(privacyPermissionMemberId()).roles("USER")).param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isForbidden());
    }
}
