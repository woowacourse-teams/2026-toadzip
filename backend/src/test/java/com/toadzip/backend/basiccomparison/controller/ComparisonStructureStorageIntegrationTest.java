package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.ComparisonFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ComparisonStructureStorageIntegrationTest extends ComparisonIntegrationSupport {
    @Test
    void 미수집_필수자격과_지역을_신청경로에_연결해_저장하고_공개한다() throws Exception {
        long announcement = announcement();
        ObjectNode content = expandedContent();
        JsonNode draft = response(save(announcement, content).andExpect(status().isOk()));
        long ruleSet = draft.path("id").asLong();

        assertThat(jdbc.queryForObject(
                "SELECT exact_name FROM comparison_qualifications WHERE rule_set_id = ?",
                String.class, ruleSet)).isEqualTo("지원대상 한부모가족");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comparison_path_qualifications WHERE rule_set_id = ?",
                Long.class, ruleSet)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comparison_supply_units WHERE rule_set_id = ?"
                        + " AND region_codes @> ARRAY['48240']::text[]",
                Long.class, ruleSet)).isOne();
        publish(announcement, draft).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comparison_options WHERE rule_set_id = ?",
                Long.class, ruleSet)).isOne();
    }

    @Test
    void 없는_필수자격을_참조하면_공고와_관계형_행을_저장하지_않는다() throws Exception {
        long announcement = announcement();
        ObjectNode content = expandedContent();
        content.putArray("qualifications");
        save(announcement, content).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ?",
                Long.class, announcement)).isZero();
    }

    @Test
    void 같은_단위의_경로별_공유물량과_임대조건_일정_적용범위를_보존한다() throws Exception {
        long announcement = announcement();
        ObjectNode content = expandedContent();
        ObjectNode secondPath = (ObjectNode) content.path("paths").get(0).deepCopy();
        secondPath.put("id", "second");
        ((ArrayNode) content.path("paths")).add(secondPath);
        ObjectNode secondOption = (ObjectNode) content.path("options").get(0).deepCopy();
        String firstOption = secondOption.path("id").asString();
        secondOption.put("id", "second-option").put("pathId", "second");
        ((ArrayNode) content.path("options")).add(secondOption);
        ObjectNode quantity = (ObjectNode) content.path("quantities").get(2);
        quantity.putArray("supplyUnitIds");
        quantity.putArray("optionIds").add(firstOption).add("second-option");
        ((ObjectNode) content.path("rentOffers").get(0)).putArray("supplyUnitIds");
        ((ObjectNode) content.path("rentOffers").get(0)).putArray("optionIds").add(firstOption);
        ((ObjectNode) content.path("schedules").get(0)).putArray("supplyUnitIds");
        ((ObjectNode) content.path("schedules").get(0)).putArray("optionIds").add("second-option");
        JsonNode draft = response(save(announcement, content).andExpect(status().isOk()));
        publish(announcement, draft).andExpect(status().isOk());
        JsonNode copy = response(mvc.perform(post(admin(announcement) + "/copy")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isCreated()));
        assertThat(copy.path("content").path("rentOffers").get(0).path("supplyUnitIds").size()).isZero();
        assertThat(copy.path("content").path("schedules").get(0).path("supplyUnitIds").size()).isZero();
        assertThat(copy.path("content").path("quantities").get(2).path("optionIds").size()).isEqualTo(2);
        assertThat(copy.path("content").path("rentOffers").get(0).path("optionIds").get(0).asString())
                .isEqualTo(firstOption);
        assertThat(copy.path("content").path("schedules").get(0).path("optionIds").get(0).asString())
                .isEqualTo("second-option");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comparison_options WHERE rule_set_id = ?",
                Long.class, copy.path("id").asLong())).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 투영_저장_실패는_초안_내용과_기존_관계형_행을_함께_롤백한다() throws Exception {
        long announcement = transaction(this::announcement);
        try {
            JsonNode draft = saveDraft(announcement, null, 0, ComparisonFixtures.sacheon());
            long ruleSet = draft.path("id").asLong();
            String before = jdbc.queryForObject(
                    "SELECT content::text FROM basic_comparison_rule_sets WHERE id = ?", String.class, ruleSet);
            jdbc.execute("ALTER TABLE comparison_qualifications ADD CONSTRAINT fail_test_qualification"
                    + " CHECK (rule_set_id <> " + ruleSet + ")");
            mvc.perform(put(admin(announcement)).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedDraftId", ruleSet,
                                    "expectedRevision", 1, "content", expandedContent()))))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject(
                    "SELECT content::text FROM basic_comparison_rule_sets WHERE id = ?", String.class, ruleSet))
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject(
                    "SELECT revision FROM basic_comparison_rule_sets WHERE id = ?", Long.class, ruleSet)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM comparison_supply_units WHERE rule_set_id = ?"
                            + " AND cardinality(region_codes) = 0", Long.class, ruleSet)).isOne();
        } finally {
            jdbc.execute("ALTER TABLE comparison_qualifications DROP CONSTRAINT IF EXISTS fail_test_qualification");
            jdbc.update("DELETE FROM announcements WHERE id = ?", announcement);
        }
    }

    private org.springframework.test.web.servlet.ResultActions save(long announcement, ObjectNode content)
            throws Exception {
        return mvc.perform(put(admin(announcement))
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedRevision", 0, "content", content))));
    }

    private ObjectNode expandedContent() {
        // Synthetic storage example: qualification identity and links, not an actual D05 route.
        ObjectNode content = (ObjectNode) json.valueToTree(ComparisonFixtures.sacheonWithInformation());
        ((ObjectNode) content.path("metadata")).put("accessMode", "APPLICATION_ROUTES");
        ObjectNode path = (ObjectNode) content.path("paths").get(0);
        path.put("kind", "APPLICATION");
        path.putArray("qualificationIds").add("supported-parent");
        ((ObjectNode) content.path("supplyUnits").get(0)).putArray("regionCodes").add("48240");
        content.set("qualifications", json.valueToTree(List.of(Map.of(
                "id", "supported-parent", "exactName", "지원대상 한부모가족",
                "legalBasis", "원문 법정 지원대상 기준", "conditionsAsWritten", "지원대상 증명 확인",
                "categories", List.of("SINGLE_PARENT_FAMILY"), "evidenceIds", List.of("source")))));
        content.putArray("commonChecks");
        return content;
    }
}
