package com.toadzip.backend.ingest.source.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

    private static final String ENDPOINT = "/api/admin/ingest/sources";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void storeSources() {
        jdbc.sql("""
                INSERT INTO myhome_complex_source (source_key, hsmp_nm, hsmp_sn, collected_at)
                VALUES ('complex-key', '두꺼비 단지', 123, :collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
        jdbc.sql("""
                INSERT INTO lh_catalog_source (source_order, complex_label, collected_at)
                VALUES (1, '두꺼비 임대', :collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
        jdbc.sql("""
                INSERT INTO myhome_announcement_source
                    (source_key, pblanc_id, pblanc_nm, pc_url, active, consecutive_miss_count, collected_at)
                VALUES ('announcement-key', 'MYHOME-123', '두꺼비 공고', 'https://official.test/myhome', false, 2,
                        :collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_catalog_source
                    (source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
                     announcement_type_code, supply_info_type_code, content_fingerprint, raw_payload,
                     changed_at, collected_at, present_in_latest_catalog)
                VALUES ('lh-key', 'LH-123', '03', '06', '060', '01', 'fingerprint',
                        '{"PAN_NM":"두꺼비 LH 공고","DTL_URL":"https://official.test/lh","EXTRA_FIELD":"보존값"}',
                        :changedAt, :collectedAt, false)
                """).param("changedAt", Timestamp.from(Instant.parse("2026-10-01T01:00:00Z")))
                .param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_detail_source
                    (source_order, pan_id, dataset_type, complex_name, url, collected_at)
                VALUES (1, 'LH-123', 'COMPLEX', '두꺼비 상세', 'https://official.test/attachment.pdf', :collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
        jdbc.sql("""
                INSERT INTO lh_announcement_supply_source (source_order, pan_id, complex_label, collected_at)
                VALUES (1, 'LH-123', '두꺼비 공급', :collectedAt)
                """).param("collectedAt", Timestamp.from(Instant.parse("2026-10-02T01:00:00Z"))).update();
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
                    + "https://example.test/lh/lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1"
    })
    void 관리자는_여섯_종류의_저장된_원천을_출처_URL과_함께_조회한다(
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
        jdbc.sql("UPDATE myhome_announcement_source SET url = 'https://official.test/preferred'").update();
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
                INSERT INTO myhome_complex_source (source_key, hsmp_nm, collected_at)
                VALUES ('second-key', '두꺼비 신규', :collectedAt), ('last-key', '두꺼비 최신', :collectedAt),
                       ('other-key', '다른 단지', :collectedAt)
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
                .andExpect(jsonPath("$.data.items[0].sourceKey").value("complex-key"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 식별자_검색은_SQL_와일드카드를_문자로_취급한다() throws Exception {
        jdbc.sql("""
                INSERT INTO myhome_complex_source (source_key, hsmp_nm) VALUES ('complex%_key', '특수문자 단지')
                """).update();
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("category", "MYHOME_COMPLEX").param("keyword", "%_"))
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
                UPDATE myhome_complex_source SET hsmp_sn = 9007199254740993, bass_rent_gtn = 9007199254740993
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
                UPDATE lh_announcement_catalog_source SET raw_payload =
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
                UPDATE myhome_complex_source
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
                UPDATE lh_announcement_catalog_source SET raw_payload =
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
        mockMvc.perform(get(ENDPOINT).with(user("member").roles("USER")).param("category", "MYHOME_COMPLEX"))
                .andExpect(status().isForbidden());
    }
}
