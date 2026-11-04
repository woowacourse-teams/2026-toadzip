package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.interest.domain.BookmarkType;
import com.toadzip.backend.interest.repository.BookmarkRepository;
import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet",
        "app.notification.publisher-enabled=true", "app.notification.activation-at=2026-01-01T00:00:00Z"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class UnconfirmedAnnouncementIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private BookmarkRepository bookmarks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private AnnouncementAttachmentRepository attachments;
    @Autowired private NotificationAudienceRepository audiences;
    @Autowired private NotificationOutboxRepository outbox;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void isolateOwnershipFromTestTransaction() {
        when(ownership.acquire()).thenAnswer(invocation -> mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 값을_모두_가진_공고도_미확정이면_공개_상세와_북마크에서_숨긴다() throws Exception {
        LocalDate date = LocalDate.of(2026, 11, 2);
        Announcement announcement = announcements.saveAndFlush(Announcement.create("SH:900002", null, null,
                "SH 값이 있는 미확정 공고", "원공고", "행복주택", "신규모집", "SH", date, date, date.plusDays(4),
                date.plusDays(30), "https://example.com/sh/900002", null, 0L, null));
        jdbc.update("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = ?", announcement.getId());
        entityManager.clear();

        mvc.perform(get("/api/v1/announcements/" + announcement.getId())).andExpect(status().isNotFound());
        assertThat(bookmarks.save(member(), BookmarkType.ANNOUNCEMENT, announcement.getId())).isFalse();
    }

    @Test
    void 미확정_공고는_관리자가_필수값을_채워_수정해도_공개하지_않고_관리자_PDF_추출은_공개_전에도_첨부를_읽는다() throws Exception {
        Announcement unconfirmed = announcements.saveAndFlush(Announcement.createUnconfirmed("SH:900001",
                "SH 미확정 공고", AgencyCode.SH, LocalDate.of(2026, 11, 2), "https://example.com/sh/900001"));
        String publicPath = "/api/v1/announcements/" + unconfirmed.getId();
        String adminPath = "/api/admin/announcements/" + unconfirmed.getId();

        AnnouncementAttachment attachment = attachments.saveAndFlush(AnnouncementAttachment.createFromSource(
                unconfirmed, "1", "공고문.pdf", AttachmentType.ANNOUNCEMENT, "https://www.i-sh.co.kr/file.pdf", 1));
        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
        assertThat(attachments.findPublicSource(unconfirmed.getId(), attachment.getId())).isEmpty();
        assertThat(attachments.findAdminSource(unconfirmed.getId(), attachment.getId())).isPresent();
        assertThat(audiences.visibleTitle(unconfirmed.getId())).isEmpty();
        assertThat(outbox.appendNewAnnouncement(unconfirmed)).isFalse();
        assertThat(bookmarks.save(member(), BookmarkType.ANNOUNCEMENT, unconfirmed.getId())).isFalse();
        var detail = json.readTree(mvc.perform(get(adminPath).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.announcement.releaseStatus").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.data.summary.rental").isEmpty())
                .andReturn().getResponse().getContentAsString()).get("data");

        var values = (ObjectNode) detail.get("data");
        values.put("rentalType", "HAPPY_HOUSING").put("recruitmentType", "NEW")
                .put("applicationStartDate", "2026-11-10").put("applicationEndDate", "2026-11-14")
                .put("winnerAnnouncementDate", "2026-12-01");
        mvc.perform(put(adminPath).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(values.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.announcement.releaseStatus").value("UNCONFIRMED"));

        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
        assertThat(outboxCount("SH:900001")).isZero();
    }

    @Test
    void 단지에_연결한_공급행_없이_공개를_요청하면_거절하고_미확정으로_둔다() throws Exception {
        long id = unconfirmedWithValues().getId();

        var request = request(id);
        request.withArray("fields").add("name");
        request.put("publish", true);
        preview(id, request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("단지에 연결")));

        assertThat(releaseStatus(id)).isEqualTo("UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"excluded", "deletedComplex"})
    void 남는_연결_공급행이_없으면_공개를_요청해도_거절한다(String change) throws Exception {
        var fixtures = new NotificationFixtures(entityManager, jdbc);
        Announcement announcement = unconfirmedWithValues();
        var complex = fixtures.complex("11140", "1114010100");
        fixtures.link(announcement, complex);
        if (change.equals("deletedComplex")) {
            jdbc.update("UPDATE housing_complexes SET admin_deleted = true WHERE id = ?", complex.getId());
        }
        entityManager.clear();
        long id = announcement.getId();

        var request = request(id);
        request.put("publish", true);
        var row = (ObjectNode) request.path("correction").path("rows").get(0);
        if (change.equals("excluded")) {
            row.put("excluded", true);
        }
        preview(id, request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("단지에 연결")));

        assertThat(releaseStatus(id)).isEqualTo("UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
    }

    @Test
    void 연결_없이_들어온_공급행을_같은_보정에서_단지에_연결하며_공개를_요청하면_공개한다() throws Exception {
        var fixtures = new NotificationFixtures(entityManager, jdbc);
        Announcement announcement = unconfirmedWithValues();
        fixtures.link(announcement, null);
        var complex = fixtures.complex("11140", "1114010100");
        entityManager.clear();
        long id = announcement.getId();

        var rename = request(id);
        rename.withArray("fields").add("name");
        preview(id, rename).andExpect(status().isOk()).andExpect(jsonPath("$.data.publishes").value(false));

        var request = request(id);
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("housingComplexId", complex.getId());
        request.put("publish", true);
        preview(id, request).andExpect(status().isOk()).andExpect(jsonPath("$.data.publishes").value(true));
        apply(id, request).andExpect(status().isOk());

        assertThat(releaseStatus(id)).isEqualTo("PUBLISHED");
        assertThat(outboxCount(announcement.getSourceAnnouncementIdentifier())).isOne();
        assertThat(jdbc.queryForObject("SELECT housing_complex_id FROM supply_rows WHERE announcement_id = ?",
                Long.class, id)).isEqualTo(complex.getId());
    }

    @Test
    void 일정과_유형이_빈_미확정_공고는_필수값을_고르고_단지를_연결해_공개를_요청해야_공개한다() throws Exception {
        var fixtures = new NotificationFixtures(entityManager, jdbc);
        Announcement announcement = announcements.saveAndFlush(Announcement.createUnconfirmed("SH:900003",
                "SH 일정 없는 미확정 공고", AgencyCode.SH, LocalDate.of(2026, 11, 2), "https://example.com/sh/900003"));
        fixtures.link(announcement, null);
        var complex = fixtures.complex("11140", "1114010100");
        entityManager.clear();
        long id = announcement.getId();

        var withoutValues = request(id);
        ((ObjectNode) withoutValues.path("correction").path("rows").get(0)).put("housingComplexId", complex.getId());
        withoutValues.put("publish", true);
        preview(id, withoutValues).andExpect(status().isBadRequest());
        assertThat(releaseStatus(id)).isEqualTo("UNCONFIRMED");

        var request = request(id);
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("housingComplexId", complex.getId());
        request.withArray("fields").add("rentalType").add("recruitmentType").add("applicationStartDate")
                .add("applicationEndDate").add("winnerAnnouncementDate");
        request.put("publish", true);
        apply(id, request).andExpect(status().isOk());

        assertThat(releaseStatus(id)).isEqualTo("PUBLISHED");
        assertThat(outboxCount("SH:900003")).isOne();
    }

    @Test
    void 단지가_미리_연결돼_있어도_관리자가_공개를_요청한_보정에서만_공개하고_새공고_알림은_한_번만_쌓는다()
            throws Exception {
        var fixtures = new NotificationFixtures(entityManager, jdbc);
        Announcement announcement = unconfirmedWithValues();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        entityManager.clear();
        long id = announcement.getId();
        String identifier = announcement.getSourceAnnouncementIdentifier();

        var rename = request(id);
        rename.withArray("fields").add("name");
        apply(id, rename).andExpect(status().isOk());
        assertThat(releaseStatus(id)).isEqualTo("UNCONFIRMED");
        assertThat(outboxCount(identifier)).isZero();

        var publishOnly = request(id);
        publishOnly.put("publish", true);
        apply(id, publishOnly).andExpect(status().isOk());
        assertThat(releaseStatus(id)).isEqualTo("PUBLISHED");
        assertThat(outboxCount(identifier)).isOne();
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk());
        assertThat(audiences.visibleTitle(id)).isPresent();

        var supplyOnly = request(id);
        ((ObjectNode) supplyOnly.path("correction").path("rows").get(0)).put("householdCount", 3);
        supplyOnly.put("publish", true);
        apply(id, supplyOnly).andExpect(status().isOk());
        assertThat(outboxCount(identifier)).isOne();
    }

    private Announcement unconfirmedWithValues() {
        Announcement announcement = new NotificationFixtures(entityManager, jdbc).announcement();
        jdbc.update("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = ?", announcement.getId());
        entityManager.clear();
        return announcement;
    }

    private ObjectNode request(long id) throws Exception {
        var state = data("/api/admin/announcements/" + id + "/json-corrections");
        var document = (ObjectNode) json.readTree(new ClassPathResource("announcement/import-v1-valid.json")
                .getInputStream());
        ((ObjectNode) document.get("announcement")).put("name", "PDF로 확인한 공고 " + UUID.randomUUID());
        var request = json.createObjectNode();
        request.put("snapshotToken", state.path("snapshotToken").asText());
        request.set("document", document);
        request.putArray("fields");
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

    private ResultActions preview(long id, ObjectNode request) throws Exception {
        return mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request.toString()));
    }

    private ResultActions apply(long id, ObjectNode request) throws Exception {
        String token = json.readTree(preview(id, request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data").path("confirmationToken").asText();
        var body = json.createObjectNode();
        body.set("request", request);
        body.put("confirmationToken", token);
        return mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    private JsonNode data(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private String releaseStatus(long id) {
        return jdbc.queryForObject("SELECT release_status FROM announcements WHERE id = ?", String.class, id);
    }

    private long outboxCount(String sourceIdentifier) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events WHERE source_identifier = ?",
                Long.class, sourceIdentifier);
    }

    private long member() {
        return jdbc.queryForObject("""
                INSERT INTO users (login_identifier, created_at) VALUES ('unconfirmed-member', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
    }
}
