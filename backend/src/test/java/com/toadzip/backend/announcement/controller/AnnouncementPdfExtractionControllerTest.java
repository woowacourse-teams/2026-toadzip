package com.toadzip.backend.announcement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.PdfExtractionJobResponse;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.exception.PdfExtractionJobNotFoundException;
import com.toadzip.backend.announcement.service.AnnouncementPdfExtractionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@WebMvcTest(AnnouncementPdfExtractionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnnouncementPdfExtractionControllerTest {

    private static final String PATH = "/api/admin/announcements/7/pdf-extractions";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};
    private static final Instant CREATED = Instant.parse("2026-11-01T01:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementPdfExtractionService service;

    @Test
    void 올린_PDF로_작업을_넣고_202와_대기_상태를_돌려준다() throws Exception {
        when(service.request(eq(7L), eq("admin"), eq("announcement.pdf"), eq(PDF))).thenReturn(pending(31L));

        mockMvc.perform(upload(PDF))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(31))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.pdfSha256").value("ab".repeat(32)))
                .andExpect(jsonPath("$.data.extraction").doesNotExist());
    }

    @Test
    void 진행_중_작업이_있으면_409를_돌려준다() throws Exception {
        when(service.request(eq(7L), any(), any(), any())).thenThrow(new PdfExtractionInProgressException());

        mockMvc.perform(upload(PDF))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_EXTRACTION_IN_PROGRESS"));
    }

    @Test
    void 빈_파일은_400으로_거절하고_작업을_넣지_않는다() throws Exception {
        mockMvc.perform(upload(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_INVALID"));
        verify(service, never()).request(anyLong(), any(), any(), any());
    }

    @Test
    void PDF가_30MB를_넘으면_400으로_거절하고_작업을_넣지_않는다() throws Exception {
        byte[] large = new byte[(int) AnnouncementPdfExtractionService.MAX_PDF_BYTES + 1];

        mockMvc.perform(upload(large))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_INVALID"));
        verify(service, never()).request(anyLong(), any(), any(), any());
    }

    @Test
    void 읽을_수_없는_PDF는_400과_이유를_돌려준다() throws Exception {
        when(service.request(eq(7L), any(), any(), any()))
                .thenThrow(new InvalidAnnouncementPdfException("쪽이 없는 PDF입니다."));

        mockMvc.perform(upload(PDF))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("쪽이 없는 PDF입니다."));
    }

    @Test
    void 없는_공고는_404를_돌려준다() throws Exception {
        when(service.request(eq(7L), any(), any(), any())).thenThrow(new AnnouncementNotFoundException());

        mockMvc.perform(upload(PDF))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_NOT_FOUND"));
    }

    @Test
    void 실패한_작업은_실패_사유를_돌려준다() throws Exception {
        when(service.job(7L, 31L)).thenReturn(new PdfExtractionJobResponse(31L, "FAILED", "공고문.pdf",
                "ab".repeat(32), 3, CREATED, CREATED.plusSeconds(600),
                new PdfExtractionJobResponse.Failure("MODEL_UNAVAILABLE", "AI 모델 호출에 계속 실패했습니다."), null, null));

        mockMvc.perform(get(PATH + "/jobs/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.attempts").value(3))
                .andExpect(jsonPath("$.data.failure.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.completedAt").value("2026-11-01T01:10:00Z"));
    }

    @Test
    void 추출을_취소하면_POST_cancel이_끝낸_작업의_실패_사유를_돌려준다() throws Exception {
        when(service.cancel(7L, 31L)).thenReturn(new PdfExtractionJobResponse(31L, "FAILED", "공고문.pdf",
                "ab".repeat(32), 0, CREATED, CREATED.plusSeconds(60),
                new PdfExtractionJobResponse.Failure("CANCELLED_BY_ADMIN", "관리자가 추출을 취소했습니다. 다시 요청할 수 있습니다."),
                null, null));

        mockMvc.perform(post(PATH + "/jobs/31/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure.code").value("CANCELLED_BY_ADMIN"));
        verify(service).cancel(7L, 31L);
    }

    @Test
    void 재시도_대기_작업은_재시도_사유를_돌려준다() throws Exception {
        when(service.job(7L, 31L)).thenReturn(new PdfExtractionJobResponse(31L, "RETRY", "공고문.pdf",
                "ab".repeat(32), 1, CREATED, null, null,
                new PdfExtractionJobResponse.Failure("UPSTREAM_TIMEOUT", "추출 서비스 응답이 늦습니다."), null));

        mockMvc.perform(get(PATH + "/jobs/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retryReason.message").value("추출 서비스 응답이 늦습니다."))
                .andExpect(jsonPath("$.data.failure").doesNotExist());
    }

    @Test
    void 첨부_PDF로_작업을_넣고_202를_돌려준다() throws Exception {
        when(service.requestAttachment(7L, "admin", 68L)).thenReturn(pending(31L));

        mockMvc.perform(post(PATH + "/attachments/68").principal(new TestingAuthenticationToken("admin", null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(31));
    }

    @Test
    void 첨부를_받지_못하면_첨부_오류_코드와_상태를_돌려준다() throws Exception {
        when(service.requestAttachment(7L, "admin", 68L))
                .thenThrow(new AttachmentUnavailableException(AttachmentUnavailableException.Reason.NOT_PDF));

        mockMvc.perform(post(PATH + "/attachments/68").principal(new TestingAuthenticationToken("admin", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_PDF"));
    }

    @Test
    void 이_공고의_작업이_아니면_404를_돌려준다() throws Exception {
        when(service.job(7L, 31L)).thenThrow(new PdfExtractionJobNotFoundException());

        mockMvc.perform(get(PATH + "/jobs/31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_EXTRACTION_JOB_NOT_FOUND"));
    }

    @Test
    void 진행_중_작업을_돌려준다() throws Exception {
        when(service.active(7L)).thenReturn(pending(31L));

        mockMvc.perform(get(PATH + "/jobs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(31));
    }

    @Test
    void 가장_최근에_성공한_추출_결과를_돌려준다() throws Exception {
        when(service.latest(7L)).thenReturn(new SavedAnnouncementPdfExtraction("공고문.pdf", "ab".repeat(32),
                Instant.parse("2026-10-26T01:00:00Z"), new AnnouncementPdfExtraction("{\"data\":{}}", List.of(1, 2),
                35, List.of(), new AnnouncementPdfExtraction.Usage(900, 300, null))));

        mockMvc.perform(get(PATH + "/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("공고문.pdf"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-26T01:00:00Z"))
                .andExpect(jsonPath("$.data.extraction.totalPages").value(35));
    }

    @Test
    void 성공한_추출_결과가_없으면_data를_비운다() throws Exception {
        mockMvc.perform(get(PATH + "/latest"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":null}", JsonCompareMode.STRICT));
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] content) {
        var request = multipart(PATH).file(new MockMultipartFile("file", "announcement.pdf", "application/pdf",
                content));
        request.principal(new TestingAuthenticationToken("admin", null));
        return request;
    }

    private static PdfExtractionJobResponse pending(long jobId) {
        return new PdfExtractionJobResponse(jobId, "PENDING", "공고문.pdf", "ab".repeat(32), 0, CREATED, null,
                null, null, null);
    }
}
