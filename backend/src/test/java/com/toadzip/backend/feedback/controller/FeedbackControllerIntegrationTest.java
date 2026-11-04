package com.toadzip.backend.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
    void 의견이_없어도_설문을_저장한다(String content) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT content FROM feedback").query(String.class).single()).isEmpty();
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

    @ParameterizedTest
    @ValueSource(strings = {"VERY_DISAPPOINTED", "SOMEWHAT_DISAPPOINTED", "NOT_DISAPPOINTED", "NO_LONGER_USING"})
    void 설문_답변을_저장하고_관리자가_조회한다(String answer) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Submission(null, answer))))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT survey_answer FROM feedback").query(String.class).single()).isEqualTo(answer);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/feedback")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].surveyAnswer").value(answer))
                .andExpect(jsonPath("$.data.items[0].content").value(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"content\":\"의견\"}", "{\"surveyAnswer\":null}",
            "{\"surveyAnswer\":\"UNKNOWN\"}", "{\"surveyAnswer\":1}"})
    void 설문_답변이_없거나_유효하지_않으면_저장하지_않는다(String body) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM feedback").query(Long.class).single()).isZero();
    }

    private String body(String content) {
        return objectMapper.writeValueAsString(new Submission(content, "VERY_DISAPPOINTED"));
    }

    private record Submission(String content, String surveyAnswer) {
    }
}
