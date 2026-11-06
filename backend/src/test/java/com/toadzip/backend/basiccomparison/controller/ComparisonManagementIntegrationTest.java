package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.ComparisonFixtures;
import com.toadzip.backend.basiccomparison.domain.ComparisonContent;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class ComparisonManagementIntegrationTest extends ComparisonIntegrationSupport {
    @Test
    void 관리자_권한과_CSRF를_유지한다() throws Exception {
        mvc.perform(get(admin(1))).andExpect(status().isUnauthorized());
        mvc.perform(get(admin(1)).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(admin(1) + "/copy").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 공급_임대_일정_근거를_공개하고_그대로_복사한다() throws Exception {
        long announcement = announcement();
        ComparisonContent content = ComparisonFixtures.sacheonWithInformation();
        JsonNode draft = saveDraft(announcement, null, 0, content);
        publish(announcement, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value(3))
                .andExpect(jsonPath("$.data.content.quantities[2].value").value(110))
                .andExpect(jsonPath("$.data.content.rentOffers[1].depositMinimum").value(7648000))
                .andExpect(
                        jsonPath("$.data.content.schedules[0].targetAsWritten")
                                .value("신청 구분별 지정일 확인, 선순위 초과 시 후순위 미접수"));
        JsonNode copied = copy(announcement);
        assertThat(json.treeToValue(copied.path("content"), ComparisonContent.class))
                .usingRecursiveComparison()
                .withComparatorForType(
                        java.util.Comparator.comparing(java.time.OffsetDateTime::toInstant),
                        java.time.OffsetDateTime.class)
                .isEqualTo(content);
        publish(announcement, copied)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        publish(announcement, draft).andExpect(status().isConflict());
        assertThat(count(announcement, "PUBLISHED")).isOne();
        assertThat(count(announcement, "DRAFT")).isZero();
    }

    @Test
    void 초안_버전을_확인하고_오래된_수정과_공개를_거부한다() throws Exception {
        long announcement = announcement();
        JsonNode draft = saveDraft(announcement, null, 0, ComparisonFixtures.sacheon());
        JsonNode changed =
                saveDraft(announcement, draft.path("id").asLong(), 1, ComparisonFixtures.sacheon());
        publish(announcement, draft).andExpect(status().isConflict());
        mvc.perform(
                        put(admin(announcement))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "expectedDraftId",
                                                        draft.path("id").asLong(),
                                                        "expectedRevision",
                                                        1,
                                                        "content",
                                                        ComparisonFixtures.sacheon()))))
                .andExpect(status().isConflict());
        publish(announcement, changed).andExpect(status().isOk());
    }

    @Test
    void 공개_검토_미완료는_기존_공개본을_교체하지_않는다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, ComparisonFixtures.sacheon()))
                .andExpect(status().isOk());
        String before = publishedContent(announcement);
        JsonNode copied = copy(announcement);
        ObjectNode reviewing = (ObjectNode) copied.path("content").deepCopy();
        ((ObjectNode) reviewing.path("metadata")).put("reviewStatus", "REVIEWING");
        JsonNode changed =
                saveDraft(
                        announcement,
                        copied.path("id").asLong(),
                        1,
                        json.treeToValue(reviewing, ComparisonContent.class));
        publish(announcement, changed)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BASIC_COMPARISON_INVALID"));
        assertThat(publishedContent(announcement)).isEqualTo(before);
        assertThat(count(announcement, "DRAFT")).isOne();
    }

    @Test
    void 원문_근거_단절과_제외된_선정필드는_저장하지_않는다() throws Exception {
        long announcement = announcement();
        ObjectNode bad = (ObjectNode) json.valueToTree(ComparisonFixtures.sacheon());
        ((ObjectNode) bad.path("evidences").get(0)).put("documentId", "missing");
        rejectContent(announcement, bad);
        ObjectNode unsupported = (ObjectNode) json.valueToTree(ComparisonFixtures.sacheon());
        unsupported.putArray("selectionRules").add("rank");
        rejectContent(announcement, unsupported);
        assertThat(count(announcement, "DRAFT")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"취소공고", "CANCELLATION"})
    void 정정공개는_이전공개를_대체하고_취소와_이전공고_재공개는_거부한다(String cancelledStatus) throws Exception {
        long original = announcement();
        publish(original, saveDraft(original, null, 0, ComparisonFixtures.sacheon()))
                .andExpect(status().isOk());
        long corrected = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '정정공고' WHERE id ="
                        + " ?",
                original,
                corrected);
        publish(corrected, saveDraft(corrected, null, 0, ComparisonFixtures.sacheon()))
                .andExpect(status().isOk());
        assertThat(count(original, "PUBLISHED")).isZero();
        publish(original, saveDraft(original, null, 0, ComparisonFixtures.sacheon()))
                .andExpect(status().isConflict());
        JsonNode draft = copy(corrected);
        long cancelled = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = ? WHERE id = ?",
                corrected,
                cancelledStatus,
                cancelled);
        publish(corrected, draft).andExpect(status().isConflict());
        publish(cancelled, saveDraft(cancelled, null, 0, ComparisonFixtures.sacheon()))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 공개_교체_중_DB실패는_삭제까지_롤백한다() throws Exception {
        long announcement = transaction(this::announcement);
        try {
            publish(announcement, saveDraft(announcement, null, 0, ComparisonFixtures.sacheon()))
                    .andExpect(status().isOk());
            String before = publishedContent(announcement);
            JsonNode copied = copy(announcement);
            jdbc.execute(
                    "ALTER TABLE basic_comparison_rule_sets ADD CONSTRAINT test_fail_publication"
                            + " CHECK (announcement_id <> "
                            + announcement
                            + " OR version < 2)");
            publish(announcement, copied)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
            assertThat(publishedContent(announcement)).isEqualTo(before);
            assertThat(count(announcement, "DRAFT")).isOne();
        } finally {
            jdbc.execute(
                    "ALTER TABLE basic_comparison_rule_sets DROP CONSTRAINT IF EXISTS"
                            + " test_fail_publication");
            jdbc.update("DELETE FROM announcements WHERE id = ?", announcement);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_초안의_동시_공개는_한번만_성공한다() throws Exception {
        long announcement = transaction(this::announcement);
        try {
            JsonNode draft = saveDraft(announcement, null, 0, ComparisonFixtures.sacheon());
            CyclicBarrier barrier = new CyclicBarrier(2);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var actions =
                        java.util.stream.IntStream.range(0, 2)
                                .mapToObj(
                                        index ->
                                                executor.submit(
                                                        () -> {
                                                            barrier.await(5, TimeUnit.SECONDS);
                                                            return publish(announcement, draft)
                                                                    .andReturn()
                                                                    .getResponse()
                                                                    .getStatus();
                                                        }))
                                .toList();
                assertThat(
                                List.of(
                                        actions.get(0).get(20, TimeUnit.SECONDS),
                                        actions.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            }
            assertThat(count(announcement, "PUBLISHED")).isOne();
        } finally {
            jdbc.update("DELETE FROM announcements WHERE id = ?", announcement);
        }
    }

    private JsonNode copy(long announcement) throws Exception {
        return response(
                mvc.perform(
                                post(admin(announcement) + "/copy")
                                        .with(user("admin").roles("ADMIN"))
                                        .with(csrf()))
                        .andExpect(status().isCreated()));
    }

    private long count(long announcement, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ? AND"
                        + " status = ?",
                Long.class,
                announcement,
                status);
    }

    private String publishedContent(long announcement) {
        return jdbc.queryForObject(
                "SELECT content::text FROM basic_comparison_rule_sets WHERE announcement_id = ? "
                        + "AND status = 'PUBLISHED'",
                String.class,
                announcement);
    }

    private void rejectContent(long announcement, ObjectNode content) throws Exception {
        mvc.perform(
                        put(admin(announcement))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("expectedRevision", 0, "content", content))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BASIC_COMPARISON_INVALID"));
    }
}
