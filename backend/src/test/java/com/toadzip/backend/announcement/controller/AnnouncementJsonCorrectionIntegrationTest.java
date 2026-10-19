package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class AnnouncementJsonCorrectionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void lease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 기존_공고의_JSON_갱신은_미리보기_후_선택한_기본정보와_공급정보만_함께_저장한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        String identifier = announcement.getSourceAnnouncementIdentifier();
        var request = request(id);
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("householdCount", 3);
        var row = (ObjectNode) request.path("correction").path("rows").get(0);
        row.put("areaOverride", true).put("exclusiveArea", 36.5).put("supplyArea", 49.8);
        var preview = preview(id, request);
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
        assertThat(jdbc.queryForObject("SELECT total_supply_household_count FROM supply_rows WHERE announcement_id=?",
                Integer.class, id)).isEqualTo(10);
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.name").value("JSON으로 확인한 공고"));
        entities.clear();
        var stored = entities.find(Announcement.class, id);
        assertThat(stored.getSourceAnnouncementIdentifier()).isEqualTo(identifier);
        assertThat(stored.getOriginalUrl()).isEqualTo(announcement.getOriginalUrl());
        assertThat(jdbc.queryForObject("SELECT total_supply_household_count FROM supply_rows WHERE announcement_id=?",
                Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT announcement_exclusive_area FROM supply_rows WHERE announcement_id=?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("36.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isConflict());
    }

    private Announcement fixture() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        fixtures.link(announcement, null);
        entities.flush();
        return announcement;
    }

    @Test
    void 선택한_일정과_첨부만_갱신하고_기존_다른_항목과_재정제_보호를_유지한다() throws Exception {
        var announcement = fixture();
        var event = com.toadzip.backend.announcement.domain.AnnouncementSchedule.createFromSource(
                announcement, "LH:old:event", com.toadzip.backend.announcement.domain.ScheduleType.APPLICATION,
                "기존 접수", java.time.LocalDateTime.of(2026, 10, 20, 9, 0),
                java.time.LocalDateTime.of(2026, 10, 21, 18, 0), 0);
        var attachment = com.toadzip.backend.announcement.domain.AnnouncementAttachment.createFromSource(
                announcement, "LH:old:file", "기존 공고문.pdf",
                com.toadzip.backend.announcement.domain.AttachmentType.ANNOUNCEMENT,
                "https://example.com/old.pdf", 0);
        var kept = com.toadzip.backend.announcement.domain.AnnouncementAttachment.create(announcement,
                "기존 참고.pdf", com.toadzip.backend.announcement.domain.AttachmentType.REFERENCE,
                "https://example.com/reference.pdf", 1);
        entities.persist(event); entities.persist(attachment); entities.persist(kept); entities.flush();
        var request = request(announcement.getId());
        request.putArray("fields");
        request.withArray("schedules").addObject().put("inputIndex", 0).put("existingId", event.getId());
        request.withArray("attachments").addObject().put("inputIndex", 0).put("existingId", attachment.getId());
        var preview = preview(announcement.getId(), request);
        apply(announcement.getId(), request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var storedEvent = entities.find(com.toadzip.backend.announcement.domain.AnnouncementSchedule.class, event.getId());
        var storedAttachment = entities.find(com.toadzip.backend.announcement.domain.AnnouncementAttachment.class,
                attachment.getId());
        assertThat(storedEvent.getName()).isEqualTo("인터넷 접수");
        assertThat(storedEvent.isAdminModified()).isTrue();
        assertThat(storedAttachment.getFileName()).isEqualTo("공고문.pdf");
        assertThat(storedAttachment.isAdminModified()).isTrue();
        assertThat(storedAttachment.updateFromSource("덮어쓸 공고.pdf", storedAttachment.getFileType(),
                "https://example.com/overwrite.pdf", 0)).isFalse();
        assertThat(storedEvent.updateFromSource(storedEvent.getScheduleType(), "덮어쓸 일정",
                storedEvent.getStartAt(), storedEvent.getEndAt(), 0, null)).isFalse();
        assertThat(entities.find(com.toadzip.backend.announcement.domain.AnnouncementAttachment.class,
                kept.getId()).getFileName()).isEqualTo("기존 참고.pdf");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_attachments WHERE announcement_id=?",
                Long.class, announcement.getId())).isEqualTo(2);
    }

    @Test
    void 미리보기_뒤_첨부가_바뀌면_기본정보와_공급정보도_저장하지_않는다() throws Exception {
        var announcement = fixture();
        var request = request(announcement.getId());
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("householdCount", 3);
        var preview = preview(announcement.getId(), request);
        entities.persist(com.toadzip.backend.announcement.domain.AnnouncementAttachment.create(announcement,
                "늦게 수집한 자료.pdf", com.toadzip.backend.announcement.domain.AttachmentType.REFERENCE,
                "https://example.com/changed.pdf", 0));
        entities.flush();
        apply(announcement.getId(), request, preview.path("confirmationToken").asText())
                .andExpect(status().isConflict());
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
        assertThat(jdbc.queryForObject("SELECT total_supply_household_count FROM supply_rows WHERE announcement_id=?",
                Integer.class, announcement.getId())).isEqualTo(10);
    }

    @Test
    void 다른_공고의_첨부나_추출_미확인_항목은_선택_적용하지_못한다() throws Exception {
        var announcement = fixture();
        var request = request(announcement.getId());
        request.withArray("attachments").addObject().put("inputIndex", 0).put("existingId", 999999);
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        request.putArray("attachments");
        ((ObjectNode) request.get("document")).withArray("unresolvedFields").addObject()
                .put("path", "announcement.name").put("reason", "추출 미확인");
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
    }

    @Test
    void 확인된_단지별_접수일정은_JSON_기본기간으로_덮어쓰지_않는다() throws Exception {
        var announcement = fixture();
        announcement.confirmApplicationPeriod(announcement.getApplicationStartDate(), announcement.getApplicationEndDate());
        entities.flush();
        var request = request(announcement.getId());
        request.withArray("fields").add("applicationStartDate");
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        request.putArray("fields").add("name");
        var preview = preview(announcement.getId(), request);
        apply(announcement.getId(), request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        assertThat(announcement.isApplicationScheduleReviewed()).isTrue();
        assertThat(announcement.getApplicationStartDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 13));
    }

    @Test
    void 인증과_CSRF없이_기존_공고를_JSON으로_갱신하지_못한다() throws Exception {
        var announcement = fixture();
        mvc.perform(get("/api/admin/announcements/" + announcement.getId() + "/json-corrections"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).contentType("application/json")
                .content(request(announcement.getId()).toString())).andExpect(status().isForbidden());
    }

    private ObjectNode request(long id) throws Exception {
        var state = data("/api/admin/announcements/" + id + "/json-corrections");
        var document = (ObjectNode) json.readTree(Files.readString(
                Path.of("../docs/fixtures/announcement-import-v1-valid.json")));
        ((ObjectNode) document.get("announcement")).put("name", "JSON으로 확인한 공고");
        var request = json.createObjectNode();
        request.put("snapshotToken", state.path("snapshotToken").asText());
        request.set("document", document);
        request.putArray("fields").add("name");
        request.putArray("schedules");
        request.putArray("attachments");
        var correction = request.putObject("correction");
        correction.put("operationId", UUID.randomUUID().toString());
        correction.put("snapshotToken", state.path("supply").path("snapshotToken").asText());
        correction.put("reason", "공식 공고문에서 확인");
        correction.put("sourceUrl", "https://example.com/notice");
        correction.putNull("sourcePage");
        correction.set("rows", state.path("supply").path("rows").deepCopy());
        return request;
    }

    private JsonNode data(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode preview(long id, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("data");
    }

    private org.springframework.test.web.servlet.ResultActions apply(long id, ObjectNode request, String token)
            throws Exception {
        var body = json.createObjectNode(); body.set("request", request); body.put("confirmationToken", token);
        return mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body.toString()));
    }
}
