package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/db/migration/V20261016_01__quality_comparison_workflow.sql",
        "/db/migration/V20261103_03__announcement_correction_drafts.sql"})
class CorrectionDraftIntegrationTest {
    private static final String SHA = "ab".repeat(32);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 저장한_초안을_다시_불러오면_마지막으로_저장한_작업_상태를_그대로_돌려준다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        var first = payload("첫 작업");
        save("admin", id, body(null, token, first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));
        var second = payload("두 번째 작업");
        second.putObject("rows").putObject("row:1").put("mode", "SPLIT").putNull("count").put("manual", "59.91");
        save("admin", id, body(1L, token, second).put("extractionId", 7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));

        var draft = draft("admin", id);

        assertThat(draft.path("payload")).isEqualTo(second);
        assertThat(draft.path("revision").asLong()).isEqualTo(2);
        assertThat(draft.path("baseSnapshotToken").asText()).isEqualTo(token);
        assertThat(draft.path("pdfSha256").asText()).isEqualTo(SHA);
        assertThat(draft.path("extractionId").asLong()).isEqualTo(7);
        assertThat(draft.path("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");
        assertThat(draft.path("stale").asBoolean()).isFalse();
    }

    @Test
    void 오래된_revision으로_저장하면_409와_현재_revision을_돌려주고_최신_초안을_유지한다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        save("admin", id, body(null, token, payload("탭 A"))).andExpect(status().isOk());
        save("admin", id, body(1L, token, payload("탭 A 다음"))).andExpect(status().isOk());
        String updatedAt = draft("admin", id).path("updatedAt").asText();

        save("admin", id, body(1L, token, payload("탭 B 늦은 저장"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(2))
                .andExpect(jsonPath("$.currentUpdatedAt").value(updatedAt));
        save("admin", id, body(null, token, payload("탭 C 첫 저장"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentRevision").value(2));

        var draft = draft("admin", id);
        assertThat(draft.path("revision").asLong()).isEqualTo(2);
        assertThat(draft.path("payload").path("note").asText()).isEqualTo("탭 A 다음");
    }

    @Test
    void 삭제는_본_revision이_최신일_때만_지우고_이미_없으면_성공으로_본다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        save("admin", id, body(null, token, payload("작업"))).andExpect(status().isOk());
        save("admin", id, body(1L, token, payload("작업 다음"))).andExpect(status().isOk());

        remove("admin", id, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.currentRevision").value(2));
        assertThat(draft("admin", id).path("revision").asLong()).isEqualTo(2);
        remove("admin", id, 2).andExpect(status().isNoContent());
        assertThat(draft("admin", id).isNull()).isTrue();
        remove("admin", id, 2).andExpect(status().isNoContent());
        // 다른 탭이 지운 초안을 늦게 저장해 되살리지 않는다.
        save("admin", id, body(2L, token, payload("지운 뒤 늦은 저장"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentRevision").isEmpty());
        assertThat(draft("admin", id).isNull()).isTrue();
    }

    @Test
    void 다른_관리자의_초안은_읽거나_덮어쓰거나_지우지_못한다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        save("admin", id, body(null, token, payload("관리자 A"))).andExpect(status().isOk());

        assertThat(draft("other", id).isNull()).isTrue();
        save("other", id, body(1L, token, payload("관리자 B"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentRevision").isEmpty());
        remove("other", id, 1).andExpect(status().isNoContent());
        save("other", id, body(null, token, payload("관리자 B"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        var mine = draft("admin", id);
        assertThat(mine.path("revision").asLong()).isEqualTo(1);
        assertThat(mine.path("payload").path("note").asText()).isEqualTo("관리자 A");
        assertThat(draft("other", id).path("payload").path("note").asText()).isEqualTo("관리자 B");
    }

    @Test
    void 초안을_저장하고_지워도_공고와_공급정보는_바뀌지_않는다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        var announcementBefore = announcementRow(id);
        var supplyBefore = jdbc.queryForList("SELECT * FROM supply_rows WHERE announcement_id = ?", id);

        save("admin", id, body(null, token, payload("공개 데이터 불변"))).andExpect(status().isOk());
        save("admin", id, body(1L, token, payload("공개 데이터 불변 다음"))).andExpect(status().isOk());
        remove("admin", id, 2).andExpect(status().isNoContent());
        entities.clear();

        assertThat(announcementRow(id)).isEqualTo(announcementBefore);
        assertThat(jdbc.queryForList("SELECT * FROM supply_rows WHERE announcement_id = ?", id)).isEqualTo(supplyBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes", Long.class)).isZero();
        assertThat(snapshotToken(id)).isEqualTo(token);
    }

    @Test
    void 초안을_만든_뒤_공급정보가_바뀌면_오래된_기준으로_표시한다() throws Exception {
        long id = fixture().getId();
        save("admin", id, body(null, snapshotToken(id), payload("작업"))).andExpect(status().isOk());

        jdbc.update("UPDATE supply_rows SET total_supply_household_count = 3 WHERE announcement_id = ?", id);
        entities.clear();

        assertThat(draft("admin", id).path("stale").asBoolean()).isTrue();
    }

    @Test
    void 작업_상태가_2MB를_넘거나_객체가_아닌_작업_상태는_저장하지_않는다() throws Exception {
        long id = fixture().getId();
        String token = snapshotToken(id);
        var large = json.createObjectNode().put("note", "가".repeat(700_000));

        save("admin", id, body(null, token, large)).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_CORRECTION_DRAFT_TOO_LARGE"));
        var array = body(null, token, payload("배열"));
        array.putArray("payload").add(1);
        save("admin", id, array).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        save("admin", id, body(null, token, payload("잘못된 PDF")).put("pdfSha256", "not-a-sha"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        save("admin", id, body(null, token, payload("모르는 필드")).put("adminLoginIdentifier", "other"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(draft("admin", id).isNull()).isTrue();
    }

    @Test
    void 없는_공고에는_초안을_저장하지_않는다() throws Exception {
        save("admin", 999_999L, body(null, "token", payload("작업"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_NOT_FOUND"));
    }

    @Test
    void 인증과_CSRF없이_초안을_읽거나_바꾸지_못한다() throws Exception {
        long id = fixture().getId();
        String path = "/api/admin/announcements/" + id + "/correction-draft";
        String content = body(null, "token", payload("작업")).toString();

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(csrf()).contentType("application/json").content(content))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).contentType("application/json").content(content))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).param("expectedRevision", "1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_correction_drafts", Long.class)).isZero();
    }

    private Announcement fixture() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        fixtures.link(announcement, null);
        entities.flush();
        return announcement;
    }

    private ObjectNode payload(String note) {
        var payload = json.createObjectNode().put("note", note);
        payload.putObject("review").put("progress", 3).put("reason", "공고문 3쪽 공급표");
        return payload;
    }

    private ObjectNode body(Long expectedRevision, String token, ObjectNode payload) {
        var body = json.createObjectNode();
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        body.put("baseSnapshotToken", token).put("pdfSha256", SHA);
        body.set("payload", payload);
        return body;
    }

    private ResultActions save(String admin, long id, ObjectNode body) throws Exception {
        return mvc.perform(put("/api/admin/announcements/" + id + "/correction-draft")
                .with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body.toString()));
    }

    private ResultActions remove(String admin, long id, long expectedRevision) throws Exception {
        return mvc.perform(delete("/api/admin/announcements/" + id + "/correction-draft")
                .param("expectedRevision", String.valueOf(expectedRevision))
                .with(user(admin).roles("ADMIN")).with(csrf()));
    }

    private JsonNode draft(String admin, long id) throws Exception {
        return data(admin, "/api/admin/announcements/" + id + "/correction-draft");
    }

    private String snapshotToken(long id) throws Exception {
        return data("admin", "/api/admin/announcements/" + id + "/json-corrections").path("snapshotToken").asText();
    }

    private JsonNode data(String admin, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user(admin).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private Map<String, Object> announcementRow(long id) {
        return jdbc.queryForMap("SELECT * FROM announcements WHERE id = ?", id);
    }
}
