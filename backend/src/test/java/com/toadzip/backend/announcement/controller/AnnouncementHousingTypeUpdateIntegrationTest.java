package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.MyHomeRentalCondition;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
import tools.jackson.databind.node.ObjectNode;

/** PDF 보정 저장이 공고문의 주택형 이름·공급면적·세대수를 공급행이 고른 주택형에 반영하는지 확인한다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class AnnouncementHousingTypeUpdateIntegrationTest {
    /** 공고 게시일 2026-10-13의 서울 자정 */
    private static final Instant POSTED_AT = Instant.parse("2026-10-12T15:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    private Announcement announcement;
    private HousingComplex complex;
    private HousingType type;
    private SupplyRow row;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
        var fixtures = new NotificationFixtures(entities, jdbc);
        announcement = fixtures.announcement();
        complex = fixtures.complex("11140", "1114010100");
        type = HousingType.createFromMyHome(complex, "source-36", "36", new BigDecimal("36.91"), new BigDecimal("50.1"));
        entities.persist(type);
        row = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 0, "알림 테스트 단지", "36A",
                null, null, SupplyCategory.NEW_SUPPLY, null, 10);
        entities.persist(row);
        entities.flush();
    }

    @Test
    void 공급행을_바꾸지_않아도_공고_값으로_주택형을_갱신하고_단지_이력과_버전을_남긴다() throws Exception {
        long version = complex.getVersion();
        var request = request();
        value(request, "row:" + row.getId(), "36A", "50.6512", 120);

        var preview = preview(request);
        assertThat(preview.path("housingTypes")).hasSize(1);
        assertThat(preview.path("housingTypes").get(0).path("before").path("name").asText()).isEqualTo("36");
        assertThat(preview.path("housingTypes").get(0).path("after").path("householdCount").asInt()).isEqualTo(120);
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getName()).isEqualTo("36A");
        assertThat(stored.getSupplyArea()).isEqualByComparingTo("50.6512");
        assertThat(stored.getTotalHouseholdCount()).isEqualTo(120);
        assertThat(stored.getAnnouncementObservedAt()).isEqualTo(POSTED_AT);
        assertThat(stored.isNameAdminModified() || stored.isHouseholdCountAdminModified()).isFalse();
        assertThat(entities.find(HousingComplex.class, complex.getId()).getVersion()).isGreaterThan(version);
        assertThat(jdbc.queryForList("SELECT after_value FROM admin_data_changes WHERE resource_type='COMPLEX' "
                + "AND resource_id=? AND action='UPDATE_HOUSING_TYPE_FROM_PDF'", String.class, complex.getId()))
                .singleElement().asString().contains("\"householdCount\":120", "\"announcementId\":" + announcement.getId());
        assertThat(jdbc.queryForObject("SELECT after_value FROM admin_data_changes WHERE resource_type='ANNOUNCEMENT' "
                + "AND resource_id=? AND action='CORRECT_FROM_PDF'", String.class, announcement.getId()))
                .contains("\"housingTypes\"");
    }

    @Test
    void 고른_공급대상의_금액으로_주택형_기본_임대조건을_갱신하고_출처를_공고문으로_남긴다() throws Exception {
        var request = request();
        value(request, "row:" + row.getId(), null, null, null).put("basicDeposit", 8_100_000L).put("basicMonthlyRent", 156_230L);

        var preview = preview(request);
        assertThat(preview.path("housingTypes").get(0).path("after").path("basicDeposit").asLong()).isEqualTo(8_100_000L);
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getBasicRentalCondition()).isEqualTo(new MyHomeRentalCondition(8_100_000L, 156_230L, POSTED_AT));
        assertThat(stored.getRentalConditionSource()).isEqualTo("ANNOUNCEMENT");
        assertThat(jdbc.queryForList("SELECT after_value FROM admin_data_changes WHERE resource_type='COMPLEX' "
                + "AND resource_id=? AND action='UPDATE_HOUSING_TYPE_FROM_PDF'", String.class, complex.getId()))
                .singleElement().asString().contains("\"basicDeposit\":8100000", "\"basicMonthlyRent\":156230");
    }

    @Test
    void 같은_주택형을_고른_행들의_기본_임대조건이_다르면_지금_값을_유지한다() throws Exception {
        var second = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 1, "알림 테스트 단지",
                "36A", null, null, SupplyCategory.NEW_SUPPLY, null, 5);
        entities.persist(second);
        entities.flush();
        var request = request();
        value(request, "row:" + row.getId(), "36A", null, null).put("basicDeposit", 8_100_000L).put("basicMonthlyRent", 156_230L);
        value(request, "row:" + second.getId(), "36A", null, null).put("basicDeposit", 8_623_000L).put("basicMonthlyRent", 161_140L);

        var preview = preview(request);
        assertThat(preview.path("housingTypeWarnings").toString()).contains("공고 값이 서로 달라 지금 값을 유지합니다: 기본 임대조건");
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        assertThat(entities.find(HousingType.class, type.getId()).getBasicRentalCondition()).isNull();
    }

    @Test
    void 관리자가_고친_세대수는_유지하고_고치지_않은_이름은_공고로_갱신한다() throws Exception {
        type.reviseByAdmin("36", new BigDecimal("36.91"), 7);
        entities.flush();
        var request = request();
        value(request, "row:" + row.getId(), "36A", null, 120);

        var preview = preview(request);
        assertThat(preview.path("housingTypeWarnings").toString()).contains("관리자가 고친 세대수를 유지합니다");
        assertThat(preview.path("housingTypes").get(0).path("after").path("householdCount").asInt()).isEqualTo(7);
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getName()).isEqualTo("36A");
        assertThat(stored.getTotalHouseholdCount()).isEqualTo(7);
    }

    @Test
    void 더_최근_공고를_반영한_주택형은_이른_공고로_되돌리지_않는다() throws Exception {
        var later = Instant.parse("2026-10-31T15:00:00Z");
        jdbc.update("UPDATE housing_types SET announcement_observed_at=? WHERE id=?", Timestamp.from(later), type.getId());
        entities.clear();
        var request = request();
        value(request, "row:" + row.getId(), "36A", "50.6512", 120);

        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest());

        request.withArray("fields").add("name");
        var preview = preview(request);
        assertThat(preview.path("housingTypes")).isEmpty();
        assertThat(preview.path("housingTypeWarnings").toString()).contains("더 최근 공고(2026-11-01)의 주택형 값을 유지합니다");
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getName()).isEqualTo("36");
        assertThat(stored.getTotalHouseholdCount()).isNull();
        assertThat(stored.getAnnouncementObservedAt()).isEqualTo(later);
    }

    @Test
    void 새로_만드는_주택형에는_공고_세대수를_넣고_보호하지_않는다() throws Exception {
        var request = request();
        var input = (ObjectNode) request.path("correction").path("rows").get(0);
        input.putNull("housingTypeId");
        input.putObject("newHousingType").put("name", "59").put("exclusiveArea", new BigDecimal("59.9"))
                .put("supplyArea", new BigDecimal("80.2"));
        value(request, input.path("key").asText(), "59", "80.2", 44);

        var preview = preview(request);
        assertThat(preview.path("housingTypes").get(0).path("housingTypeId").isNull()).isTrue();
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var created = entities.find(SupplyRow.class, row.getId()).getHousingType();
        assertThat(created.getId()).isNotEqualTo(type.getId());
        assertThat(created.getName()).isEqualTo("59");
        assertThat(created.getTotalHouseholdCount()).isEqualTo(44);
        assertThat(created.isNameAdminModified() || created.isHouseholdCountAdminModified()).isFalse();
    }

    @Test
    void 한_주택형에_서로_다른_공고_값이_오면_그_필드만_지금_값을_유지한다() throws Exception {
        var second = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 1, "알림 테스트 단지",
                "36A", null, null, SupplyCategory.NEW_SUPPLY, null, 5);
        entities.persist(second);
        entities.flush();
        var request = request();
        value(request, "row:" + row.getId(), "36A", null, 120);
        value(request, "row:" + second.getId(), "36A", "50.6512", 121);

        var preview = preview(request);
        assertThat(preview.path("housingTypeWarnings").toString()).contains("공고 값이 서로 달라 지금 값을 유지합니다: 세대수");
        assertThat(preview.path("housingTypes")).hasSize(1);
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getName()).isEqualTo("36A");
        assertThat(stored.getSupplyArea()).isEqualByComparingTo("50.6512");
        assertThat(stored.getTotalHouseholdCount()).isNull();
    }

    @Test
    void 같은_주택형을_고른_행들이_함께_비운_값은_지금_값을_유지하고_나머지는_반영한다() throws Exception {
        var second = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 1, "알림 테스트 단지",
                "36A", null, null, SupplyCategory.NEW_SUPPLY, null, 5);
        entities.persist(second);
        entities.flush();
        var request = request();
        value(request, "row:" + row.getId(), null, null, 120);
        value(request, "row:" + second.getId(), null, null, 120);

        var preview = preview(request);
        assertThat(preview.path("housingTypeWarnings")).isEmpty();
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getName()).isEqualTo("36");
        assertThat(stored.getSupplyArea()).isEqualByComparingTo("50.1");
        assertThat(stored.getTotalHouseholdCount()).isEqualTo(120);
    }

    @Test
    void 값이_같은_공고도_다른_변경과_함께_저장하면_반영한_게시_시각을_남긴다() throws Exception {
        var request = request();
        request.withArray("fields").add("name");
        value(request, "row:" + row.getId(), "36", "50.1", null);

        var preview = preview(request);
        assertThat(preview.path("housingTypes")).isEmpty();
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        assertThat(entities.find(HousingType.class, type.getId()).getAnnouncementObservedAt()).isEqualTo(POSTED_AT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes WHERE action='UPDATE_HOUSING_TYPE_FROM_PDF'",
                Long.class)).isZero();
    }

    @Test
    void 값이_같은_공고_값만_보내도_반영한_게시_시각을_남긴다() throws Exception {
        var request = request();
        value(request, "row:" + row.getId(), "36", "50.1", null);

        var preview = preview(request);
        assertThat(preview.path("housingTypes")).isEmpty();
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        assertThat(entities.find(HousingType.class, type.getId()).getAnnouncementObservedAt()).isEqualTo(POSTED_AT);
    }

    @Test
    void 같은_저장에서_고친_게시일로_공고_순서를_판단한다() throws Exception {
        jdbc.update("UPDATE housing_types SET announcement_observed_at=? WHERE id=?",
                Timestamp.from(Instant.parse("2026-10-19T15:00:00Z")), type.getId());
        entities.clear();
        var request = request();
        ((ObjectNode) request.path("document").path("announcement")).put("postedDate", "2026-11-01");
        request.withArray("fields").add("postedDate");
        value(request, "row:" + row.getId(), null, null, 120);

        var preview = preview(request);
        assertThat(preview.path("housingTypes").findValuesAsString("rowKey")).containsExactly("row:" + row.getId());
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var stored = entities.find(HousingType.class, type.getId());
        assertThat(stored.getTotalHouseholdCount()).isEqualTo(120);
        assertThat(stored.getAnnouncementObservedAt()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    }

    @Test
    void 이미_반영한_같은_날_같은_값만_보내면_바뀐_것이_없다() throws Exception {
        jdbc.update("UPDATE housing_types SET announcement_observed_at=? WHERE id=?", Timestamp.from(POSTED_AT), type.getId());
        entities.clear();
        var request = request();
        value(request, "row:" + row.getId(), "36", "50.1", null);

        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미리보기_뒤_주택형이_바뀌면_저장하지_않는다() throws Exception {
        var request = request();
        value(request, "row:" + row.getId(), "36A", null, 120);
        var preview = preview(request);
        jdbc.update("UPDATE housing_types SET total_household_count=9 WHERE id=?", type.getId());
        entities.clear();

        apply(request, preview).andExpect(status().isConflict());
        entities.clear();
        assertThat(entities.find(HousingType.class, type.getId()).getTotalHouseholdCount()).isEqualTo(9);
    }

    @Test
    void 공급면적_없이_만드는_새_주택형에_세대수만_넣는다() throws Exception {
        var request = request();
        var input = (ObjectNode) request.path("correction").path("rows").get(0);
        input.putNull("housingTypeId");
        input.putObject("newHousingType").put("name", "59").put("exclusiveArea", new BigDecimal("59.9"));
        value(request, input.path("key").asText(), null, null, 44);

        var preview = preview(request);
        assertThat(preview.path("housingTypes").get(0).path("after").path("supplyArea").isNull()).isTrue();
        apply(request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var created = entities.find(SupplyRow.class, row.getId()).getHousingType();
        assertThat(created.getTotalHouseholdCount()).isEqualTo(44);
        assertThat(created.getSupplyArea()).isNull();
    }

    @Test
    void 한_저장에서_같은_단지_면적_새_주택형을_고른_행들은_주택형_하나를_함께_쓴다() throws Exception {
        var second = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 1, "알림 테스트 단지",
                "59", null, null, SupplyCategory.NEW_SUPPLY, null, 5);
        entities.persist(second);
        entities.flush();
        var request = request();
        for (var node : request.path("correction").path("rows")) {
            ((ObjectNode) node).putNull("housingTypeId");
            ((ObjectNode) node).putObject("newHousingType").put("name", "59").put("exclusiveArea", new BigDecimal("59.9"));
        }
        apply(request, preview(request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var first = entities.find(SupplyRow.class, row.getId()).getHousingType();
        assertThat(first.getId()).isNotEqualTo(type.getId());
        assertThat(entities.find(SupplyRow.class, second.getId()).getHousingType().getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM housing_types WHERE housing_complex_id=? AND exclusive_area=59.9",
                Long.class, complex.getId())).isEqualTo(1);
    }

    @Test
    void 휴지통_단지의_주택형에는_공고_값을_반영하지_않는다() throws Exception {
        var request = request();
        value(request, "row:" + row.getId(), "36A", null, 120);
        jdbc.update("UPDATE housing_complexes SET admin_deleted=true WHERE id=?", complex.getId());
        entities.clear();

        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("휴지통 단지의 주택형")));
    }

    @Test
    void PNU_없는_공급행을_빈_칸으로_보내도_바뀐_공급행으로_보지_않는다() throws Exception {
        var request = request();
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("pnu", "");
        request.withArray("fields").add("name");

        var preview = preview(request);
        assertThat(preview.path("supply").isNull()).isTrue();
        apply(request, preview).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT supply_pnu FROM supply_rows WHERE id=?", String.class, row.getId())).isNull();
    }

    @Test
    void 제외한_공급행에는_공고_값을_반영하지_않는다() throws Exception {
        var request = request();
        ((ObjectNode) request.path("correction").path("rows").get(0)).put("excluded", true);
        value(request, "row:" + row.getId(), "36A", null, 120);

        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_전용면적_주택형이_있거나_휴지통에_있으면_새로_만들지_않는다() throws Exception {
        var trashed = HousingType.createFromMyHome(complex, "source-59", "59", new BigDecimal("59.9"), null);
        entities.persist(trashed);
        trashed.moveToTrash();
        entities.flush();
        var request = request();
        var input = (ObjectNode) request.path("correction").path("rows").get(0);
        input.putNull("housingTypeId");
        input.putObject("newHousingType").put("name", "59").put("exclusiveArea", new BigDecimal("59.90"));

        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("휴지통에 있습니다")));

        ((ObjectNode) input.get("newHousingType")).put("exclusiveArea", new BigDecimal("36.9100"));
        mvc.perform(post(root() + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이 단지에 있습니다")));
    }

    private String root() {
        return "/api/admin/announcements/" + announcement.getId() + "/json-corrections";
    }

    private ObjectNode request() throws Exception {
        var state = json.readTree(mvc.perform(get(root()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var document = (ObjectNode) json.readTree(new ClassPathResource("announcement/import-v1-valid.json").getInputStream());
        ((ObjectNode) document.get("announcement")).put("name", "PDF로 확인한 공고");
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
        request.putArray("housingTypeValues");
        return request;
    }

    private ObjectNode value(ObjectNode request, String rowKey, String name, String supplyArea, Integer householdCount) {
        var value = request.withArray("housingTypeValues").addObject().put("rowKey", rowKey).put("name", name);
        if (supplyArea == null) { value.putNull("supplyArea"); } else { value.put("supplyArea", new BigDecimal(supplyArea)); }
        value.put("householdCount", householdCount);
        return value;
    }

    private JsonNode preview(ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post(root() + "/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("data");
    }

    private ResultActions apply(ObjectNode request, JsonNode preview) throws Exception {
        var body = json.createObjectNode();
        body.set("request", request);
        body.put("confirmationToken", preview.path("confirmationToken").asText());
        return mvc.perform(post(root()).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(body.toString()));
    }
}
