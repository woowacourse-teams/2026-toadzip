package com.toadzip.backend.announcement.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
class AnnouncementAttachmentIntegrationTest {
    private static final String SOURCE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1";
    private static final byte[] PDF = "%PDF-1.7 example".getBytes(StandardCharsets.US_ASCII);
    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private AnnouncementAttachmentClient client;
    private Announcement announcement;
    private AnnouncementAttachment attachment;

    @BeforeEach
    void setUp() {
        announcement = Announcement.create(UUID.randomUUID().toString(), null, null, "첨부 테스트",
                AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                AgencyCode.LH, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10),
                "https://apply.lh.or.kr/notice", null, 0, null, null, null);
        entityManager.persist(announcement);
        attachment = AnnouncementAttachment.create(announcement, "공고문.pdf", AttachmentType.ANNOUNCEMENT, SOURCE, 0);
        entityManager.persist(attachment);
        entityManager.flush();
        stub(PDF);
    }

    @Test
    void 미리보기는_인증없이_PDF_inline과_정확한_본문을_반환한다() throws Exception {
        mockMvc.perform(request()).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(PDF));
    }

    @Test
    void 다운로드는_원본_형식과_무관하게_attachment로_반환한다() throws Exception {
        stub(new byte[] {1, 2, 3});
        mockMvc.perform(request().param("download", "true")).andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test
    void 다른_공고의_첨부파일은_찾을_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/announcements/{id}/attachments/{attachmentId}/content",
                        Long.MAX_VALUE, attachment.getId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
        verifyNoInteractions(client);
    }

    @Test
    void 삭제된_공고의_첨부파일은_다운로드할_수_없다() throws Exception {
        announcement.moveToTrash();
        entityManager.flush();
        mockMvc.perform(request().param("download", "true")).andExpect(status().isNotFound());
        verifyNoInteractions(client);
    }

    @Test
    void 비PDF는_미리보기_오류를_JSON으로_반환한다() throws Exception {
        stub("<html>error</html>".getBytes(StandardCharsets.US_ASCII));
        mockMvc.perform(request()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_PDF"));
    }

    @Test
    void 외부_파일_오류는_성공한_파일로_위장하지_않는다() throws Exception {
        org.mockito.Mockito.doThrow(new AttachmentUnavailableException(
                AttachmentUnavailableException.Reason.UPSTREAM_FAILURE)).when(client).read(SOURCE);
        mockMvc.perform(request()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_UPSTREAM_FAILURE"));
    }

    private void stub(byte[] bytes) {
        org.mockito.Mockito.doAnswer(invocation -> {
            var file = new TemporaryAttachment(() -> {});
            file.append(ByteBuffer.wrap(bytes));
            return file;
        }).when(client).read(SOURCE);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/v1/announcements/{id}/attachments/{attachmentId}/content",
                announcement.getId(), attachment.getId());
    }
}
