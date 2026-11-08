package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
                BigDecimal.class, id)).isEqualByComparingTo("36.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
        // 공급행·기본정보 변경을 함께 저장해도 이력은 한 건이고, 작업 번호는 그 이력에 연결된다.
        assertThat(jdbc.queryForList("SELECT action FROM admin_data_changes WHERE resource_id=?", String.class, id))
                .containsExactly("CORRECT_FROM_PDF");
        assertThat(jdbc.queryForObject("SELECT change_id FROM admin_supply_corrections WHERE announcement_id=?", Long.class, id))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM admin_data_changes WHERE resource_id=?", Long.class, id));
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isConflict());
    }

    @Test
    void PDF_보정을_저장하면_이_공고에_남은_정제와_보강_실패를_모두_해결로_바꾼다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        String identifier = announcement.getSourceAnnouncementIdentifier();
        for (var row : new String[][] {
                {"myhome_announcement_mapping_failures", identifier + ":1", "HOUSING_TYPE_NOT_FOUND"},
                {"myhome_announcement_mapping_failures", identifier + ":2", "AMBIGUOUS_HOUSING_TYPE"},
                {"lh_announcement_enrichment_failures", identifier + ":lh", "HOUSING_TYPE_NOT_FOUND"},
                {"myhome_announcement_mapping_failures", "other:1", "HOUSING_TYPE_NOT_FOUND"},
                {"lh_announcement_enrichment_failures", "other:lh", "HOUSING_TYPE_NOT_FOUND"}}) {
            String owner = row[1].startsWith("other") ? "other" : identifier;
            jdbc.update(("INSERT INTO %s (source_key, source_announcement_identifier, reason, detail, status, "
                    + "occurred_at, last_occurred_at, occurrence_count, recurrence_count) "
                    + "VALUES (?, ?, ?, '실패', 'PENDING', now(), now(), 1, 0)").formatted(row[0]),
                    row[1], owner, row[2]);
        }
        var request = request(id);
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("householdCount", 3);
        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush();

        assertThat(jdbc.queryForList("""
                SELECT status FROM myhome_announcement_mapping_failures WHERE source_announcement_identifier = ?
                UNION ALL
                SELECT status FROM lh_announcement_enrichment_failures WHERE source_announcement_identifier = ?
                """, String.class, identifier, identifier)).containsOnly("RESOLVED").hasSize(3);
        assertThat(jdbc.queryForList("""
                SELECT last_resolved_by FROM myhome_announcement_mapping_failures
                WHERE source_announcement_identifier = ?
                UNION ALL
                SELECT last_resolved_by FROM lh_announcement_enrichment_failures
                WHERE source_announcement_identifier = ?
                """, String.class, identifier, identifier)).containsOnly("admin").hasSize(3);
        assertThat(jdbc.queryForList("""
                SELECT status FROM myhome_announcement_mapping_failures WHERE source_announcement_identifier = 'other'
                UNION ALL
                SELECT status FROM lh_announcement_enrichment_failures WHERE source_announcement_identifier = 'other'
                """, String.class)).containsOnly("PENDING").hasSize(2);
    }

    @Test
    void 함께_모집하는_주택형을_순서만_바꿔_보내면_공급행을_바꾼_요청으로_보지_않는다() throws Exception {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        var complex = fixtures.complex("11140", "1114010100");
        var representative = type(complex, "26A", "26.4673");
        var row = SupplyRow.create(announcement, complex, representative, UUID.randomUUID().toString(), 0, complex.getName(),
                "26A/B/C", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10);
        entities.persist(row); entities.flush();
        for (var joint : new HousingType[] {type(complex, "26B", "26.7000"), type(complex, "26C", "26.9000")}) {
            jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)", row.getId(), joint.getId());
        }
        entities.clear();
        long id = announcement.getId();
        var request = request(id);
        var links = (ArrayNode) request.path("correction").path("rows").get(0).path("linkedHousingTypes");
        assertThat(links).hasSize(2);
        var reversed = json.createArrayNode().add(links.get(1)).add(links.get(0));
        ((ObjectNode) request.path("correction").path("rows").get(0)).set("linkedHousingTypes", reversed);

        var supply = preview(id, request).path("supply");
        assertThat(supply.isNull() || supply.isMissingNode()).isTrue();
    }

    private HousingType type(HousingComplex complex, String name, String area) {
        var type = HousingType.createFromMyHome(complex, UUID.randomUUID().toString(), name, new BigDecimal(area), null);
        entities.persist(type); entities.flush();
        return type;
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
        var event = AnnouncementSchedule.createFromSource(
                announcement, "LH:old:event", ScheduleType.APPLICATION,
                "기존 접수", LocalDateTime.of(2026, 10, 20, 9, 0),
                LocalDateTime.of(2026, 10, 21, 18, 0), 0);
        var attachment = AnnouncementAttachment.createFromSource(
                announcement, "LH:old:file", "기존 공고문.pdf",
                AttachmentType.ANNOUNCEMENT,
                "https://example.com/old.pdf", 0);
        var kept = AnnouncementAttachment.create(announcement,
                "기존 참고.pdf", AttachmentType.REFERENCE,
                "https://example.com/reference.pdf", 1);
        entities.persist(event); entities.persist(attachment); entities.persist(kept); entities.flush();
        var request = request(announcement.getId());
        request.putArray("fields");
        request.withArray("schedules").addObject().put("inputIndex", 0).put("existingId", event.getId());
        request.withArray("attachments").addObject().put("inputIndex", 0).put("existingId", attachment.getId());
        var preview = preview(announcement.getId(), request);
        assertThat(supplyOwner(announcement.getId())).isEqualTo("LH_SOURCE");
        apply(announcement.getId(), request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush(); entities.clear();
        // PDF 보정은 공급행을 고치지 않아도 공고문으로 확인한 것이므로 공고문 소유가 된다(마이그레이션 백필과 같은 기준).
        assertThat(supplyOwner(announcement.getId())).isEqualTo("ANNOUNCEMENT_DOCUMENT");
        var storedEvent = entities.find(AnnouncementSchedule.class, event.getId());
        var storedAttachment = entities.find(AnnouncementAttachment.class,
                attachment.getId());
        assertThat(storedEvent.getName()).isEqualTo("인터넷 접수");
        assertThat(storedEvent.isAdminModified()).isTrue();
        assertThat(storedAttachment.getFileName()).isEqualTo("공고문.pdf");
        assertThat(storedAttachment.isAdminModified()).isTrue();
        assertThat(storedAttachment.updateFromSource("덮어쓸 공고.pdf", storedAttachment.getFileType(),
                "https://example.com/overwrite.pdf", 0)).isFalse();
        assertThat(storedEvent.updateFromSource(storedEvent.getScheduleType(), "덮어쓸 일정",
                storedEvent.getStartAt(), storedEvent.getEndAt(), 0, null)).isFalse();
        assertThat(entities.find(AnnouncementAttachment.class,
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
        entities.persist(AnnouncementAttachment.create(announcement,
                "늦게 수집한 자료.pdf", AttachmentType.REFERENCE,
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
        assertThat(announcement.getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void 접수처를_고르면_화면이_보낸_접수처_목록으로_통째로_바꾸고_예전_미확인_경로가_있으면_거절한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        var request = request(id);
        var document = (ObjectNode) request.get("document");
        document.remove("receptionPlace");
        var places = document.putArray("receptionPlaces");
        places.addObject().put("name", "LH 청약플러스").put("method", "ONLINE").putNull("address").putNull("contact")
                .put("url", "https://apply.lh.or.kr").put("guidance", "모바일 앱으로도 신청할 수 있습니다");
        places.addObject().put("name", "행복주택 견본주택").put("method", "VISIT").put("address", "서울시 강남구 1")
                .put("contact", "02-000-0000").putNull("url").put("guidance", "인터넷 취약계층만 현장 접수");
        request.putArray("fields").add("receptionPlaces");
        var preview = preview(id, request);
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.receptionPlaces.length()").value(2))
                .andExpect(jsonPath("$.data.detail.data.receptionPlaces[1].guidance").value("인터넷 취약계층만 현장 접수"));
        assertThat(jdbc.queryForList("SELECT method FROM announcement_reception_places WHERE announcement_id=? ORDER BY display_order",
                String.class, id)).containsExactly("ONLINE", "VISIT");

        // 예전 모양 document의 미확인 경로(receptionPlace.contact)도 첫 접수처 경로로 읽어 접수처 적용을 막는다.
        var legacy = request(id);
        ((ObjectNode) legacy.get("document")).withArray("unresolvedFields").addObject()
                .put("path", "receptionPlace.contact").put("reason", "연락처를 확인하지 못함");
        legacy.putArray("fields").add("receptionPlaces");
        mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(legacy.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("receptionPlaces")));
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
        var document = (ObjectNode) json.readTree(new ClassPathResource("announcement/import-v1-valid.json").getInputStream());
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

    private ResultActions apply(long id, ObjectNode request, String token)
            throws Exception {
        var body = json.createObjectNode(); body.set("request", request); body.put("confirmationToken", token);
        return mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body.toString()));
    }

    private String supplyOwner(long id) {
        return jdbc.queryForObject("SELECT supply_owner FROM announcements WHERE id = ?", String.class, id);
    }
}
