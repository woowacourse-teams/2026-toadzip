package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
@Sql(statements = {"DROP TABLE admin_comparison_checks", "DROP TABLE admin_comparison_sources"},
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AnnouncementJsonCorrectionRollbackIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private IngestExecutionOwnershipService ownership;
    @MockitoSpyBean private AdminDataChangeRepository changes;
    private long announcementId;

    @Test
    void 공급정보_저장_후_이력이_실패하면_기본정보와_일정까지_전부_롤백한다() throws Exception {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
        announcementId = new TransactionTemplate(transactions).execute(status -> {
            var fixtures = new NotificationFixtures(entities, jdbc);
            var announcement = fixtures.announcement(); fixtures.link(announcement, null);
            return announcement.getId();
        });
        String root = "/api/admin/announcements/" + announcementId + "/json-corrections";
        var state = json.readTree(mvc.perform(get(root).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode(); request.put("snapshotToken", state.get("snapshotToken").asText());
        var document = (ObjectNode) json.readTree(Files.readString(Path.of("../docs/fixtures/announcement-import-v1-valid.json")));
        ((ObjectNode) document.get("announcement")).put("name", "롤백할 이름"); request.set("document", document);
        request.putArray("fields").add("name"); request.putArray("schedules").addObject().put("inputIndex", 0);
        request.putArray("attachments").addObject().put("inputIndex", 0);
        var correction = request.putObject("correction"); correction.put("operationId", UUID.randomUUID().toString());
        correction.put("snapshotToken", state.path("supply").path("snapshotToken").asText());
        correction.put("reason", "공고 원문 대조"); correction.put("sourceUrl", "https://example.com/notice");
        correction.set("rows", state.path("supply").path("rows").deepCopy());
        ((ObjectNode) correction.path("rows").get(0)).put("householdCount", 3);
        var preview = json.readTree(mvc.perform(post(root + "/preview").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        doThrow(new IllegalStateException("audit failure fixture")).when(changes).save(argThat(change ->
                change != null && "UPDATE_FROM_JSON".equals(change.getAction())));
        var apply = json.createObjectNode(); apply.set("request", request);
        apply.put("confirmationToken", preview.get("confirmationToken").asText());
        mvc.perform(post(root).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(apply.toString())).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT name FROM announcements WHERE id=?", String.class, announcementId))
                .isEqualTo("관심 단지 새 공고");
        assertThat(jdbc.queryForObject("SELECT total_supply_household_count FROM supply_rows WHERE announcement_id=?",
                Integer.class, announcementId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_schedules WHERE announcement_id=?",
                Long.class, announcementId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_attachments WHERE announcement_id=?",
                Long.class, announcementId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_supply_corrections WHERE announcement_id=?",
                Long.class, announcementId)).isZero();
    }

    @AfterEach
    void cleanup() {
        if (announcementId == 0) { return; }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM supply_rows WHERE announcement_id=?", announcementId);
            jdbc.update("DELETE FROM announcements WHERE id=?", announcementId);
        });
    }
}
