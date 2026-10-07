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
    @ValueSource(strings = {" ", "\n\t", "\u2003"})
    void 내용이_없으면_저장하지_않는다(String content) throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 최대_2000자까지_접수한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("가".repeat(2000))))
                .andExpect(status().isCreated());
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

    private String body(String content) {
        return objectMapper.writeValueAsString(new Submission(content));
    }

    private record Submission(String content) {
    }
}
