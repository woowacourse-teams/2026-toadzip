package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet",
        "app.notification.publisher-enabled=true", "app.notification.activation-at=2026-01-01T00:00:00Z"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UnconfirmedAnnouncementIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private BookmarkRepository bookmarks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AnnouncementAttachmentRepository attachments;
    @Autowired private NotificationAudienceRepository audiences;
    @Autowired private NotificationOutboxRepository outbox;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void isolateOwnershipFromTestTransaction() {
        when(ownership.acquire()).thenAnswer(invocation -> mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 미확정_공고는_사용자에게_숨기고_관리자가_필수값을_채우면_공개하며_새공고_알림을_한_번_쌓는다_관리자_PDF_추출은_공개_전에도_첨부를_읽는다() throws Exception {
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
        assertThat(bookmarks.save(1L, BookmarkType.ANNOUNCEMENT, unconfirmed.getId())).isFalse();
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
                .andExpect(jsonPath("$.data.summary.announcement.releaseStatus").value("PUBLISHED"));
        values.put("version", values.get("version").asLong() + 1).put("name", "SH 공개 공고");
        mvc.perform(put(adminPath).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(values.toString()))
                .andExpect(status().isOk());

        mvc.perform(get(publicPath)).andExpect(status().isOk());
        assertThat(attachments.findPublicSource(unconfirmed.getId(), attachment.getId())).isPresent();
        assertThat(audiences.visibleTitle(unconfirmed.getId())).contains("SH 공개 공고");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events WHERE source_identifier = ?",
                Long.class, "SH:900001")).isOne();
    }
}
