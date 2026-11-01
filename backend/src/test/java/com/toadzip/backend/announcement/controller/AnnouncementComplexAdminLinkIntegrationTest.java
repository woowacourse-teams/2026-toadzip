package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** PDF·공급 보정에서 단지를 정하면 공고 단지가 관리자 연결이 되고, 정제를 기다리지 않고 그 공고 단지의 공급행에 반영된다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class AnnouncementComplexAdminLinkIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    private static final Instant AUTO_LINKED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private Announcement announcement;
    private HousingComplex autoComplex;
    private HousingComplex chosen;
    private HousingType chosenType;
    private AnnouncementComplex announcementComplex;
    private SupplyRow linkedRow;
    private SupplyRow emptyRow;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
        var fixtures = new NotificationFixtures(entities, jdbc);
        announcement = fixtures.announcement();
        autoComplex = fixtures.complex("11140", "1114010100");
        chosen = fixtures.complex("11140", "1114010200");
        var autoType = type(autoComplex, "26", "26.4673");
        chosenType = type(chosen, "26A", "26.4673");
        type(chosen, "36", "36.9100");
        announcementComplex = AnnouncementComplex.observe(announcement, "화성 리츠", "경기도 화성시 리츠로 1", null);
        announcementComplex.linkBySource(autoComplex, ComplexLinkMethod.AUTO, "refinement", AUTO_LINKED_AT);
        entities.persist(announcementComplex);
        linkedRow = row(autoComplex, autoType, 0);
        emptyRow = row(null, null, 1);
        entities.flush();
        jdbc.update("UPDATE supply_rows SET lh_exclusive_area = 36.91 WHERE id = ?", emptyRow.getId());
        entities.clear();
    }

    @Test
    void 단지를_바꾸면_공고_단지가_관리자_연결이_되고_단지_없던_같은_공고_단지_행도_같은_면적_주택형으로_붙는다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        assertThat(jdbc.queryForMap("SELECT housing_complex_id, link_method, linked_by FROM announcement_complexes WHERE id = ?",
                announcementComplex.getId()))
                .containsEntry("housing_complex_id", chosen.getId()).containsEntry("link_method", "ADMIN")
                .containsEntry("linked_by", "admin");
        var empty = entities.find(SupplyRow.class, emptyRow.getId());
        assertThat(empty.getHousingComplex()).extracting(HousingComplex::getId).isEqualTo(chosen.getId());
        assertThat(empty.getHousingType()).extracting(HousingType::getName).isEqualTo("36");
    }

    @Test
    void 자동_연결과_같은_단지로_저장해도_관리자가_확인한_연결이_된다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        rowOf(request, linkedRow).put("householdCount", 7);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT housing_complex_id, link_method FROM announcement_complexes WHERE id = ?",
                announcementComplex.getId()))
                .containsEntry("housing_complex_id", autoComplex.getId()).containsEntry("link_method", "ADMIN");
    }

    @Test
    void 공고문의_한_단지에_속한_행을_서로_다른_단지로_고르면_거절한다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, emptyRow), chosen.getId(), null);

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("같은 단지로 연결")));
        assertThat(jdbc.queryForObject("SELECT link_method FROM announcement_complexes WHERE id = ?", String.class,
                announcementComplex.getId())).isEqualTo("AUTO");
    }

    @Test
    void 정한_단지에_새로_추가한_행은_그_단지에_연결된_공고_단지를_가리킨다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());
        var extra = rowOf(request, linkedRow).deepCopy();
        extra.putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B");
        ((ArrayNode) request.get("rows")).add(extra);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT announcement_complex_id FROM supply_rows WHERE announcement_id = ? "
                + "AND source_supply_row_identifier LIKE 'MANUAL:%'", Long.class, id))
                .containsExactly(announcementComplex.getId());
    }

    @Test
    void 연결을_바꾸면_이전_연결과_결정_시각을_이력으로_남긴다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());

        var preview = preview(id, request);
        assertThat(preview.get("warnings").toString()).contains("화성 리츠: 공고 단지를", "행 1개도");
        apply(id, request, preview).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT linked_at FROM announcement_complexes WHERE id = ?", Instant.class,
                announcementComplex.getId())).isAfter(AUTO_LINKED_AT);
        var history = jdbc.queryForList("SELECT action, before_value, after_value FROM admin_data_changes "
                + "WHERE resource_type = 'ANNOUNCEMENT_COMPLEX' AND resource_id = ?", announcementComplex.getId());
        assertThat(history).hasSize(1);
        var change = history.getFirst();
        assertThat(change.get("action")).isEqualTo("LINK_BY_ADMIN");
        assertThat(json.readTree(change.get("before_value").toString()).get("linkMethod").asText()).isEqualTo("AUTO");
        assertThat(json.readTree(change.get("before_value").toString()).get("housingComplexId").asLong())
                .isEqualTo(autoComplex.getId());
        assertThat(json.readTree(change.get("after_value").toString()).get("housingComplexId").asLong())
                .isEqualTo(chosen.getId());
    }

    @Test
    void 손대지_않은_다른_공고_단지와_제외한_행은_연결_판정에_넣지_않는다() throws Exception {
        var other = AnnouncementComplex.observe(entities.find(Announcement.class, announcement.getId()), "다른 단지", null, null);
        other.linkBySource(entities.find(HousingComplex.class, autoComplex.getId()), ComplexLinkMethod.MIGRATED,
                "migration", Instant.now());
        entities.persist(other);
        var otherRow = row(entities.find(HousingComplex.class, autoComplex.getId()), null, 2, other);
        var staleRow = row(entities.find(HousingComplex.class, autoComplex.getId()), null, 3, announcementComplex());
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        rowOf(request, staleRow).put("excluded", true);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT link_method FROM announcement_complexes WHERE id = ?", String.class,
                other.getId())).isEqualTo("MIGRATED");
        assertThat(jdbc.queryForObject("SELECT link_method FROM announcement_complexes WHERE id = ?", String.class,
                announcementComplex.getId())).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT housing_complex_id FROM supply_rows WHERE id = ?", Long.class,
                otherRow.getId())).isEqualTo(autoComplex.getId());
    }

    @Test
    void 이번에_단지를_비운_행은_같은_공고_단지의_단지로_다시_채우지_않는다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var clear = request(id);
        rowOf(clear, emptyRow).putNull("housingComplexId").putNull("housingTypeId");
        rowOf(clear, emptyRow).put("householdCount", 3);
        apply(id, clear, preview(id, clear)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT housing_complex_id FROM supply_rows WHERE id = ?", Long.class,
                emptyRow.getId())).isNull();
        // 같은 단지로 다시 확인한 관리자 연결은 바뀌지 않아 이력이 더 쌓이지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes WHERE resource_type = "
                + "'ANNOUNCEMENT_COMPLEX' AND resource_id = ?", Long.class, announcementComplex.getId())).isEqualTo(1L);
    }

    @Test
    void 공고_단지가_없는_기존_행은_이_저장에서_공고_단지를_채우지_않는다() throws Exception {
        var manual = row(entities.find(HousingComplex.class, chosen.getId()), null, 4, null);
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());
        rowOf(request, manual).put("householdCount", 5);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT announcement_complex_id FROM supply_rows WHERE id = ?", Long.class,
                manual.getId())).isNull();
    }

    @Test
    void 같은_전용면적_주택형이_둘이면_단지만_맞추고_주택형은_비워_둔다() throws Exception {
        type(entities.find(HousingComplex.class, chosen.getId()), "36B", "36.9100");
        entities.flush();
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        var stored = jdbc.queryForMap("SELECT housing_complex_id, housing_type_id FROM supply_rows WHERE id = ?",
                emptyRow.getId());
        assertThat(stored.get("housing_complex_id")).isEqualTo(chosen.getId());
        assertThat(stored.get("housing_type_id")).isNull();
    }

    @Test
    void 새_행의_단지에_연결된_공고_단지가_둘이면_공고_단지를_비워_둔다() throws Exception {
        var other = AnnouncementComplex.observe(entities.find(Announcement.class, announcement.getId()), "다른 단지", null, null);
        other.linkBySource(entities.find(HousingComplex.class, chosen.getId()), ComplexLinkMethod.AUTO, "refinement",
                Instant.now());
        entities.persist(other);
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        select(rowOf(request, linkedRow), chosen.getId(), chosenType.getId());
        var extra = rowOf(request, linkedRow).deepCopy();
        extra.putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B");
        ((ArrayNode) request.get("rows")).add(extra);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT announcement_complex_id FROM supply_rows WHERE announcement_id = ? "
                + "AND source_supply_row_identifier LIKE 'MANUAL:%'", Long.class, id)).containsExactly((Long) null);
    }

    @Test
    void 관리자_연결은_정제가_다시_계산하지_않는다() {
        var stored = entities.find(AnnouncementComplex.class, announcementComplex.getId());
        stored.linkByAdmin(entities.find(HousingComplex.class, chosen.getId()), "admin", Instant.now());

        assertThat(stored.linkBySource(entities.find(HousingComplex.class, autoComplex.getId()),
                ComplexLinkMethod.AUTO, "refinement", Instant.now())).isFalse();
        assertThat(stored.getLinkMethod()).isEqualTo(ComplexLinkMethod.ADMIN);
        assertThat(stored.getHousingComplex().getId()).isEqualTo(chosen.getId());
    }

    private HousingType type(HousingComplex complex, String name, String area) {
        var type = HousingType.createFromMyHome(complex, UUID.randomUUID().toString(), name, new BigDecimal(area), null);
        entities.persist(type);
        return type;
    }

    private SupplyRow row(HousingComplex complex, HousingType type, int order) {
        return row(complex, type, order, announcementComplex);
    }

    private AnnouncementComplex announcementComplex() {
        return entities.find(AnnouncementComplex.class, announcementComplex.getId());
    }

    private SupplyRow row(HousingComplex complex, HousingType type, int order, AnnouncementComplex target) {
        var row = SupplyRow.create(entities.find(Announcement.class, announcement.getId()), complex, type,
                UUID.randomUUID().toString(), order, "화성 리츠", order == 0 ? "26" : "36", null, null,
                SupplyCategory.NEW_SUPPLY, null, 10);
        row.fillAnnouncementComplex(target);
        entities.persist(row);
        return row;
    }

    private ObjectNode rowOf(ObjectNode request, SupplyRow row) {
        for (var item : request.get("rows")) {
            if (item.path("id").asLong() == row.getId()) { return (ObjectNode) item; }
        }
        throw new IllegalStateException("row not found");
    }

    private void select(ObjectNode row, long complexId, Long typeId) {
        row.put("housingComplexId", complexId);
        if (typeId == null) { row.putNull("housingTypeId"); } else { row.put("housingTypeId", typeId); }
    }

    private ObjectNode request(long id) throws Exception {
        var workspace = json.readTree(mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공식 공고문에서 단지를 확인함");
        request.put("sourceUrl", "https://example.com/notice.pdf"); request.put("sourcePage", "3");
        request.set("rows", workspace.get("rows").deepCopy());
        return request;
    }

    private JsonNode preview(long id, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private ResultActions apply(long id, ObjectNode request, JsonNode preview) throws Exception {
        var body = json.createObjectNode().set("correction", request);
        body.put("confirmationToken", preview.get("confirmationToken").asText());
        return mvc.perform(post(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String root(long id) { return "/api/admin/announcements/" + id + "/supply-corrections"; }
}
