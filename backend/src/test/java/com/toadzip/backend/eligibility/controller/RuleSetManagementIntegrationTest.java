package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.DongtanFixtures;
import com.toadzip.backend.eligibility.domain.RuleSetContent;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RuleSetManagementIntegrationTest extends EligibilityIntegrationSupport {
    @Test
    void 관리자_권한과_CSRF를_확인한다() throws Exception {
        mvc.perform(get(admin(1))).andExpect(status().isUnauthorized());
        mvc.perform(get(admin(1)).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(admin(1) + "/copy").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 정정공개는_이전_공개본을_삭제하고_취소후_공개는_거부한다() throws Exception {
        long original = announcement();
        publish(original, saveDraft(original, null, 0, DongtanFixtures.content()))
                .andExpect(status().isOk());
        long corrected = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '정정공고' WHERE id ="
                    + " ?",
                original,
                corrected);
        publish(corrected, saveDraft(corrected, null, 0, DongtanFixtures.content()))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM announcement_rule_sets WHERE announcement_id"
                                    + " = ?",
                                Long.class,
                                original))
                .isZero();
        JsonNode draft =
                response(
                        mvc.perform(
                                        post(admin(corrected) + "/copy")
                                                .with(user("admin").roles("ADMIN"))
                                                .with(csrf()))
                                .andExpect(status().isCreated()));
        long cancellation = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '취소공고' WHERE id ="
                    + " ?",
                corrected,
                cancellation);
        publish(corrected, draft).andExpect(status().isConflict());
    }

    @Test
    void 공개_검증에_실패하면_기존_공개본과_스냅숏을_보존한다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, DongtanFixtures.content()))
                .andExpect(status().isOk());
        String before =
                jdbc.queryForObject(
                        "SELECT evaluation_snapshot::text FROM announcement_rule_sets"
                                + " WHERE announcement_id = ? AND status = 'PUBLISHED'",
                        String.class,
                        announcement);
        JsonNode copied =
                response(
                        mvc.perform(
                                        post(admin(announcement) + "/copy")
                                                .with(user("admin").roles("ADMIN"))
                                                .with(csrf()))
                                .andExpect(status().isCreated()));
        ObjectNode bad = (ObjectNode) copied.path("content").deepCopy();
        ((ObjectNode) bad.path("evidences").get(0)).put("pdfPageTo", 999);
        JsonNode changed =
                saveDraft(
                        announcement,
                        copied.path("id").asLong(),
                        copied.path("revision").asLong(),
                        json.treeToValue(bad, RuleSetContent.class));
        publish(announcement, changed)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_INVALID"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT evaluation_snapshot::text FROM announcement_rule_sets"
                                        + " WHERE announcement_id = ? AND status = 'PUBLISHED'",
                                String.class,
                                announcement))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM announcement_rule_sets WHERE announcement_id"
                                        + " = ?",
                                Long.class,
                                announcement))
                .isEqualTo(2);
    }

    @Test
    void 원천_첨부파일이_삭제되어도_남은_원문_정보로_공개본을_초안에_복사한다() throws Exception {
        long announcement = announcement();
        long attachment =
                jdbc.queryForObject(
                        """
                        INSERT INTO announcement_attachments (announcement_id, display_order, file_name, file_type, file_url)
                        VALUES (?, 1, 'fixture.pdf', 'ANNOUNCEMENT', 'https://example.com/fixture.pdf') RETURNING id
                        """,
                        Long.class,
                        announcement);
        ObjectNode content = (ObjectNode) json.valueToTree(DongtanFixtures.content());
        ((ObjectNode) content.path("documents").get(0)).put("attachmentId", attachment);
        publish(
                        announcement,
                        saveDraft(
                                announcement,
                                null,
                                0,
                                json.treeToValue(content, RuleSetContent.class)))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM announcement_attachments WHERE id = ?", attachment);

        mvc.perform(
                        post(admin(announcement) + "/copy")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content.documents[0].attachmentId").isEmpty())
                .andExpect(
                        jsonPath("$.data.content.documents[0].fileName")
                                .value("D01-case-fixture.pdf"));
    }

    @Test
    void 공개_교체는_옛_소속행을_삭제하고_지연된_초안_요청을_거부한다() throws Exception {
        long announcement = announcement();
        JsonNode first = saveDraft(announcement, null, 0, DongtanFixtures.content());
        publish(announcement, first).andExpect(status().isOk());
        JsonNode copied =
                response(
                        mvc.perform(
                                        post(admin(announcement) + "/copy")
                                                .with(user("admin").roles("ADMIN"))
                                                .with(csrf()))
                                .andExpect(status().isCreated()));
        publish(announcement, copied)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        publish(announcement, first).andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM conditions WHERE rule_set_id = ?",
                                Long.class,
                                first.path("id").asLong()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM announcements WHERE id = ?",
                                Long.class,
                                announcement))
                .isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_초안_동시_공개는_한번만_성공한다() throws Exception {
        long announcement = transaction(this::announcement);
        try {
            JsonNode draft = saveDraft(announcement, null, 0, DongtanFixtures.content());
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
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM announcement_rule_sets WHERE"
                                            + " announcement_id = ?",
                                    Long.class,
                                    announcement))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM announcements WHERE id = ?", announcement);
            jdbc.update("DELETE FROM users WHERE id = ?", USER);
        }
    }
}
