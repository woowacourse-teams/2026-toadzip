package com.toadzip.backend.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FeedbackControllerIntegrationTest {

    private static final String ENDPOINT = "/api/v1/feedback";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void 비로그인_사용자의_의견과_접수_시각을_저장한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("  검색이 불편해요.\n지역 검색을 개선해 주세요.  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber());

        assertThat(jdbc.sql("SELECT content FROM feedback").query(String.class).single())
                .isEqualTo("검색이 불편해요.\n지역 검색을 개선해 주세요.");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM feedback WHERE created_at IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t", "\u2003", "\u00a0", "\u202f", "\ufeff"})
    void 빈_의견은_거절하고_저장하지_않는다(String content) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM feedback").query(Long.class).single()).isZero();
    }

    @Test
    void 최대_2000자까지_접수한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("가".repeat(2000))))
                .andExpect(status().isCreated());
    }

    @Test
    void 유니코드_양끝_공백을_제거하고_본문의_줄바꿈은_보존한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("\u00a0\ufeff검색 개선\n지역 선택\u202f")))
                .andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT content FROM feedback").query(String.class).single())
                .isEqualTo("검색 개선\n지역 선택");
    }

    @Test
    void 최대_길이를_넘으면_거절한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("가".repeat(2001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csrf_없이_접수할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body("개선해 주세요")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 의견을_저장하고_관리자가_설문_없이_조회한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("지도 검색 개선")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/admin/feedback").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value("지도 검색 개선"))
                .andExpect(jsonPath("$.data.items[0].surveyAnswer").doesNotExist());
    }

    @Test
    void 이전_화면이_보낸_설문_답변은_무시하고_의견만_저장한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"의견\",\"surveyAnswer\":\"UNKNOWN\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT content FROM feedback").query(String.class).single()).isEqualTo("의견");
    }

    @Test
    void 공고나_단지_상세에서_보낸_의견은_대상과_함께_저장되고_관리자가_대상_이름을_본다() throws Exception {
        // 단지와 공고가 같은 ID여도 종류별로 자기 이름을 찾는지 보려고 둘의 ID를 맞춘다.
        long complex = 900_001L;
        long announcement = 900_001L;
        jdbc.sql("UPDATE housing_complexes SET id = :same WHERE id = :id")
                .param("same", complex).param("id", complex("가나 행복주택")).update();
        jdbc.sql("UPDATE announcements SET id = :same WHERE id = :id")
                .param("same", announcement).param("id", announcement("가나 행복주택 입주자 모집")).update();
        submitTarget("단지 주소가 달라요", "COMPLEX", complex).andExpect(status().isCreated());
        long announcementFeedback = idOf(submitTarget("모집 호수가 달라요", "ANNOUNCEMENT", announcement)
                .andExpect(status().isCreated()));
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("대상 없는 의견")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/feedback").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.content=='단지 주소가 달라요')].relatedResource").value("COMPLEX"))
                .andExpect(jsonPath("$.data.items[?(@.content=='단지 주소가 달라요')].relatedId").value((int) complex))
                .andExpect(jsonPath("$.data.items[?(@.content=='단지 주소가 달라요')].relatedName").value("가나 행복주택"))
                .andExpect(jsonPath("$.data.items[?(@.content=='모집 호수가 달라요')].relatedName")
                        .value("가나 행복주택 입주자 모집"))
                .andExpect(jsonPath("$.data.items[?(@.content=='대상 없는 의견')].relatedResource").value(""))
                .andExpect(jsonPath("$.data.items[?(@.content=='대상 없는 의견')].relatedName").value((Object) null));
        mockMvc.perform(get("/api/admin/feedback/" + announcementFeedback).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relatedResource").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.data.relatedId").value((int) announcement))
                .andExpect(jsonPath("$.data.relatedName").value("가나 행복주택 입주자 모집"));
    }

    @Test
    void 보던_사이_삭제된_단지나_공개되지_않은_공고도_대상으로_접수하고_이름을_보인다() throws Exception {
        long deletedComplex = complex("철거 예정 단지");
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id").param("id", deletedComplex).update();
        long unpublished = announcement("공개 전 공고");
        jdbc.sql("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = :id").param("id", unpublished).update();
        submitTarget("단지가 사라졌어요", "COMPLEX", deletedComplex).andExpect(status().isCreated());
        submitTarget("공고가 안 보여요", "ANNOUNCEMENT", unpublished).andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/feedback").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.content=='단지가 사라졌어요')].relatedName").value("철거 예정 단지"))
                .andExpect(jsonPath("$.data.items[?(@.content=='공고가 안 보여요')].relatedName").value("공개 전 공고"));
    }

    @Test
    void 병합으로_지워진_단지는_대표_단지로_접수하고_이미_접수된_의견도_대표_단지로_보인다() throws Exception {
        long target = complex("대표 단지");
        long earlierDonor = complex("먼저 접수된 뒤 병합된 단지");
        long feedbackBeforeMerge = idOf(submitTarget("병합 전에 보낸 의견", "COMPLEX", earlierDonor)
                .andExpect(status().isCreated()));
        long donorOpenedByUser = complex("보던 사이 병합된 단지");
        merge(earlierDonor, target);
        merge(donorOpenedByUser, target);

        submitTarget("병합 직후에 보낸 의견", "COMPLEX", donorOpenedByUser).andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT related_id FROM feedback WHERE content = '병합 직후에 보낸 의견'")
                .query(Long.class).single()).isEqualTo(target);
        mockMvc.perform(get("/api/admin/feedback/" + feedbackBeforeMerge).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relatedId").value((int) target))
                .andExpect(jsonPath("$.data.relatedName").value("대표 단지"));
        mockMvc.perform(get("/api/admin/feedback").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.items[?(@.content=='병합 직후에 보낸 의견')].relatedName").value("대표 단지"));
    }

    @Test
    void 없는_공고나_단지를_대상으로_보내면_거절하고_저장하지_않는다() throws Exception {
        long missing = complex("지워질 단지") + 1_000;
        submitTarget("의견", "COMPLEX", missing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("relatedId"));
        submitTarget("의견", "ANNOUNCEMENT", missing).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM feedback").query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"content\":\"의견\",\"relatedResource\":\"COMPLEX\"}",
            "{\"content\":\"의견\",\"relatedId\":1}",
            "{\"content\":\"의견\",\"relatedResource\":\"REGION\",\"relatedId\":1}",
            "{\"content\":\"의견\",\"relatedResource\":\"\",\"relatedId\":1}",
            "{\"content\":\"의견\",\"relatedResource\":\"COMPLEX\",\"relatedId\":0}"})
    void 대상의_종류와_ID가_짝이_맞지_않거나_형식이_틀리면_거절한다(String request) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM feedback").query(Long.class).single()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions submitTarget(String content, String resource, long id)
            throws Exception {
        return mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TargetSubmission(content, resource, id))));
    }

    private long idOf(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private long complex(String name) {
        return jdbc.sql("""
                INSERT INTO housing_complexes (source_complex_identifier, name, admin_deleted, supply_type, provider,
                    road_address, pnu, legal_dong_code, province_code, city_county_district_code, latitude, longitude,
                    total_household_count, parking_space_count)
                VALUES (:identifier, :name, false, '행복주택', 'LH',
                    '서울특별시 서대문구', 'pnu', '11110', '11', '110', 37.56, 126.96, 10, 10) RETURNING id
                """).param("identifier", "FEEDBACK-" + name).param("name", name).query(Long.class).single();
    }

    /** 단지 병합처럼 기부 단지를 지우고 그 ID를 대표 단지의 별칭으로 남긴다. */
    private void merge(long donor, long target) {
        jdbc.sql("DELETE FROM housing_complexes WHERE id = :id").param("id", donor).update();
        jdbc.sql("INSERT INTO housing_complex_aliases (id, housing_complex_id) VALUES (:donor, :target)")
                .param("donor", donor).param("target", target).update();
    }

    private long announcement(String name) {
        return jdbc.sql("""
                INSERT INTO announcements (source_announcement_identifier, name, status,
                    supply_type, recruitment_type, provider, posted_date, application_start_date, application_end_date,
                    winner_announcement_date, original_url, application_schedule_reviewed, lh_pan_id_reviewed,
                    lh_reception_place_owned, view_count)
                VALUES (:identifier, :name, 'ORIGINAL', 'HAPPY_HOUSING', 'NEW', 'LH',
                    '2026-01-01', '2026-01-01', '2026-01-02', '2026-01-03', 'https://official.test/feedback',
                    FALSE, FALSE, FALSE, 0) RETURNING id
                """).param("identifier", "FEEDBACK-" + name).param("name", name).query(Long.class).single();
    }

    private record TargetSubmission(String content, String relatedResource, long relatedId) {
    }

    private String body(String content) {
        return objectMapper.writeValueAsString(new Submission(content));
    }

    private record Submission(String content) {
    }
}
