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
import com.toadzip.backend.announcement.domain.SupplySheetSeed;
import com.toadzip.backend.announcement.fixture.SupplySheetInputs;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
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

/** PDF·공급 보정에서 단지를 정하면 공고 단지가 관리자 연결이 되고, 정제를 기다리지 않고 그 공고 단지의 주택형에 반영된다. */
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
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());

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
    void 연결은_그대로이고_단지_없던_행만_붙여도_그_행의_이전_이후를_이력으로_남긴다() throws Exception {
        long id = announcement.getId();
        var first = request(id);
        select(typeOf(first, linkedRow), chosen.getId(), chosenType.getId());
        apply(id, first, preview(id, first)).andExpect(status().isOk());
        // 다음 정제 전 단지가 비어 들어온 같은 공고 단지 행을 흉내 낸다.
        jdbc.update("UPDATE supply_rows SET housing_complex_id = NULL, housing_type_id = NULL WHERE id = ?", emptyRow.getId());
        entities.clear();

        var second = request(id);
        winner(second, linkedRow, 7);
        apply(id, second, preview(id, second)).andExpect(status().isOk());

        var history = jdbc.queryForList("SELECT after_value FROM admin_data_changes WHERE resource_type = 'ANNOUNCEMENT_COMPLEX' "
                + "AND resource_id = ? ORDER BY id", String.class, announcementComplex.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).contains("\"id\":" + emptyRow.getId()).contains("\"housingComplexId\":" + chosen.getId());
    }

    @Test
    void 자동_연결과_같은_단지로_저장해도_관리자가_확인한_연결이_된다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        winner(request, linkedRow, 7);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT housing_complex_id, link_method FROM announcement_complexes WHERE id = ?",
                announcementComplex.getId()))
                .containsEntry("housing_complex_id", autoComplex.getId()).containsEntry("link_method", "ADMIN");
    }

    @Test
    void 공고문의_한_단지에_속한_행을_서로_다른_단지로_고르면_거절한다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(typeOf(request, emptyRow), chosen.getId(), null);

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("같은 단지로 연결")));
        assertThat(jdbc.queryForObject("SELECT link_method FROM announcement_complexes WHERE id = ?", String.class,
                announcementComplex.getId())).isEqualTo("AUTO");
    }

    @Test
    void 단지를_정해_새로_추가한_주택형은_그_단지에_연결된_공고_단지를_가리킨다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        addHousingType(request, "26B", autoComplex.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(newTypeComplexes(id)).containsExactly(announcementComplex.getId());
    }

    @Test
    void 연결을_바꾸면_이전_연결과_결정_시각을_이력으로_남긴다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());

        var preview = preview(id, request);
        assertThat(preview.get("warnings").toString()).contains("화성 리츠: 공고 단지를", "주택형 1개도");
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
        remove(request, staleRow);
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());

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
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var clear = request(id);
        typeOf(clear, emptyRow).putNull("housingComplexId").putNull("housingTypeId");
        winner(clear, emptyRow, 3);
        apply(id, clear, preview(id, clear)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT housing_complex_id FROM supply_rows WHERE id = ?", Long.class,
                emptyRow.getId())).isNull();
        // 같은 단지로 다시 확인한 관리자 연결은 바뀌지 않아 이력이 더 쌓이지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes WHERE resource_type = "
                + "'ANNOUNCEMENT_COMPLEX' AND resource_id = ?", Long.class, announcementComplex.getId())).isEqualTo(1L);
    }

    @Test
    void 공고_단지가_없는_기존_행은_이_저장에서_같은_이름의_공고_단지에_든다() throws Exception {
        // 공고↔단지 연결은 공고 단지로만 보이므로, PDF 보정 저장은 공고 단지 없는 행을 (공고, 단지명) 공고 단지에 넣는다.
        var manual = row(entities.find(HousingComplex.class, chosen.getId()), null, 4, null);
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());
        winner(request, manual, 5);

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT announcement_complex_id FROM supply_rows WHERE id = ?", Long.class,
                manual.getId())).isEqualTo(announcementComplex.getId());
    }

    @Test
    void 같은_전용면적_주택형이_둘이면_이름이_다를_때_먼저_만든_주택형에_붙인다() throws Exception {
        // 마이홈 단지에는 같은 면적 주택형이 여럿인 경우가 있다. 관리자가 고르지 않아도 되도록 하나를 정한다.
        type(entities.find(HousingComplex.class, chosen.getId()), "36B", "36.9100");
        entities.flush();
        long id = announcement.getId();
        var request = request(id);
        select(typeOf(request, linkedRow), chosen.getId(), chosenType.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        var stored = jdbc.queryForMap("SELECT housing_complex_id, housing_type_id FROM supply_rows WHERE id = ?",
                emptyRow.getId());
        assertThat(stored.get("housing_complex_id")).isEqualTo(chosen.getId());
        assertThat(stored.get("housing_type_id")).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM housing_types WHERE housing_complex_id = ? AND name = '36'", Long.class, chosen.getId()));
    }

    @Test
    void 새_주택형은_그_단지에_연결된_공고_단지가_둘이어도_같은_이름의_공고_단지에_든다() throws Exception {
        var other = AnnouncementComplex.observe(entities.find(Announcement.class, announcement.getId()), "다른 단지", null, null);
        other.linkBySource(entities.find(HousingComplex.class, autoComplex.getId()), ComplexLinkMethod.AUTO, "refinement",
                Instant.now());
        entities.persist(other);
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        addHousingType(request, "26B", autoComplex.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(newTypeComplexes(id)).containsExactly(announcementComplex.getId());
    }

    @Test
    void 새_단지명의_주택형은_새_공고_단지를_만들어_고른_단지에_관리자_연결한다() throws Exception {
        long id = announcement.getId();
        var request = request(id);
        addHousingType(request, " 새 공고문 단지 ", "26B", chosen.getId());

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("""
                SELECT ac.source_complex_name || ':' || ac.housing_complex_id || ':' || ac.link_method || ':'
                    || ac.linked_by
                FROM supply_rows r JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                WHERE r.announcement_id = ? AND COALESCE(r.announcement_housing_type_name, r.source_housing_type_name)
                    = '26B'
                """, String.class, id)).containsExactly("새 공고문 단지:" + chosen.getId() + ":ADMIN:admin");
    }

    @Test
    void 같은_새_단지명의_주택형을_서로_다른_단지로_고르면_거절하고_공고_단지를_만들지_않는다() throws Exception {
        long id = announcement.getId();
        // 두 단지 모두 이 공고의 어느 공고 단지에도 이어지지 않아 두 주택형은 단지명으로 같은 새 공고 단지에 든다.
        var other = new NotificationFixtures(entities, jdbc).complex("11140", "1114010300");
        entities.flush();
        var request = request(id);
        addHousingType(request, "새 공고문 단지", "26B", chosen.getId());
        addHousingType(request, "새 공고문 단지", "36B", other.getId());

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("새 공고문 단지 · 36B: 공고문의 한 단지에 속한 주택형은")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_complexes WHERE announcement_id = ?",
                Long.class, id)).isOne();
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
                SupplyCategory.NEW_SUPPLY, null);
        row.fillAnnouncementComplex(target);
        entities.persist(row);
        new SupplySheetSeed(entities).row(row, 10);
        return row;
    }

    /** 요청 공급표에서 이 공급행의 주택형 줄. */
    private ObjectNode typeOf(ObjectNode request, SupplyRow row) {
        for (var item : request.get("sheet").get("housingTypes")) {
            if (item.path("id").asLong() == row.getId()) { return (ObjectNode) item; }
        }
        throw new IllegalStateException("housing type not found");
    }

    private void select(ObjectNode type, long complexId, Long typeId) {
        type.put("housingComplexId", complexId);
        if (typeId == null) { type.putNull("housingTypeId"); } else { type.put("housingTypeId", typeId); }
    }

    /** 이 주택형 칸들만의 당첨 수를 바꾼다. */
    private void winner(ObjectNode request, SupplyRow row, int count) {
        var cells = cellKeys(request, row);
        for (var quota : request.get("sheet").get("quotas")) {
            var members = new ArrayList<String>();
            quota.get("members").forEach(member -> members.add(member.asString()));
            if ("WINNER".equals(quota.get("kind").asString()) && members.equals(cells)) {
                ((ObjectNode) quota).put("count", count);
                return;
            }
        }
        throw new IllegalStateException("winner quota not found");
    }

    /** 주택형과 그 칸을 공급표에서 뺀다. 그 칸만 멤버였던 묶음도 뺀다. */
    private void remove(ObjectNode request, SupplyRow row) {
        var cells = cellKeys(request, row);
        var sheet = (ObjectNode) request.get("sheet");
        removeIf(sheet.withArray("housingTypes"), type -> type.path("id").asLong() == row.getId());
        removeIf(sheet.withArray("cells"), cell -> cells.contains(cell.get("key").asString()));
        for (var name : List.of("quotas", "leaseTerms")) {
            for (var item : sheet.withArray(name)) {
                removeIf((ArrayNode) item.get("members"), member -> cells.contains(member.asString()));
            }
            removeIf(sheet.withArray(name), item -> item.get("members").isEmpty());
        }
    }

    /** 단지만 정한 새 주택형을 공급표의 첫 공급대상 칸과 당첨 수 4로 더한다. */
    private void addHousingType(ObjectNode request, String name, long complexId) {
        addHousingType(request, "화성 리츠", name, complexId);
    }

    private void addHousingType(ObjectNode request, String complexName, String name, long complexId) {
        var sheet = (ObjectNode) request.get("sheet");
        String key = "new:" + name;
        sheet.withArray("housingTypes").addObject().put("key", key).putNull("id").putNull("announcementComplexId")
                .put("complexName", complexName).put("housingComplexId", complexId).putNull("housingTypeId")
                .putNull("newHousingType").put("name", name).putNull("exclusiveArea").putNull("supplyArea")
                .putNull("expectedMoveInMonth").putNull("supplyPnu").put("category", "NEW_SUPPLY").put("displayOrder", 9);
        sheet.withArray("cells").addObject().put("key", key + ":cell").putNull("id").put("housingType", key)
                .put("target", sheet.get("targets").get(0).get("key").asString()).put("displayOrder", 0);
        var quota = sheet.withArray("quotas").addObject().put("kind", "WINNER").put("count", 4);
        quota.putArray("members").add(key + ":cell");
    }

    private List<String> cellKeys(ObjectNode request, SupplyRow row) {
        var keys = new ArrayList<String>();
        for (var cell : request.get("sheet").get("cells")) {
            if (cell.get("housingType").asString().equals("row:" + row.getId())) { keys.add(cell.get("key").asString()); }
        }
        return keys;
    }

    private static void removeIf(ArrayNode array, Predicate<JsonNode> test) {
        for (int index = array.size() - 1; index >= 0; index--) {
            if (test.test(array.get(index))) { array.remove(index); }
        }
    }

    /** 새로 더한 26B 주택형의 공고 단지. */
    private List<Long> newTypeComplexes(long id) {
        return jdbc.queryForList("SELECT announcement_complex_id FROM supply_rows WHERE announcement_id = ? "
                + "AND COALESCE(announcement_housing_type_name, source_housing_type_name) = '26B'", Long.class, id);
    }

    private ObjectNode request(long id) throws Exception {
        var workspace = json.readTree(mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공식 공고문에서 단지를 확인함");
        request.put("sourceUrl", "https://example.com/notice.pdf"); request.put("sourcePage", "3");
        request.set("sheet", SupplySheetInputs.json(workspace.get("sheet")));
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
