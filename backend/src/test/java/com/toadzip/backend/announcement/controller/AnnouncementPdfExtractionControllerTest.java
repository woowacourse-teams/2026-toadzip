package com.toadzip.backend.announcement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.exception.PdfExtractionFailedException;
import com.toadzip.backend.announcement.exception.PdfExtractionUnavailableException;
import com.toadzip.backend.announcement.service.AnnouncementPdfExtractionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnnouncementPdfExtractionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnnouncementPdfExtractionControllerTest {

    private static final String PATH = "/api/admin/announcements/7/pdf-extractions";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementPdfExtractionService service;

    @Test
    void 올린_PDF의_추출_결과를_돌려준다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenReturn(new AnnouncementPdfExtraction("{\"data\":{}}", List.of(1, 2),
                35, List.of("경고"), new AnnouncementPdfExtraction.Usage(900, 300, 50)));

        mockMvc.perform(multipart(PATH).file(file(PDF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.json").value("{\"data\":{}}"))
                .andExpect(jsonPath("$.data.pages[1]").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(35))
                .andExpect(jsonPath("$.data.warnings[0]").value("경고"))
                .andExpect(jsonPath("$.data.usage.thinkingTokens").value(50));
    }

    @Test
    void 올린_파일_이름으로_추출한다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenReturn(new AnnouncementPdfExtraction("{}", List.of(1), 1,
                List.of(), new AnnouncementPdfExtraction.Usage(null, null, null)));

        mockMvc.perform(multipart(PATH).file(file(PDF))).andExpect(status().isOk());

        verify(service).extract(eq(7L), eq(PDF), eq("announcement.pdf"));
    }

    @Test
    void 가장_최근에_저장한_추출_결과를_돌려준다() throws Exception {
        when(service.latest(7L)).thenReturn(new SavedAnnouncementPdfExtraction("공고문.pdf", "ab".repeat(32),
                Instant.parse("2026-10-26T01:00:00Z"), new AnnouncementPdfExtraction("{\"data\":{}}", List.of(1, 2),
                35, List.of(), new AnnouncementPdfExtraction.Usage(900, 300, null))));

        mockMvc.perform(get(PATH + "/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("공고문.pdf"))
                .andExpect(jsonPath("$.data.pdfSha256").value("ab".repeat(32)))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-26T01:00:00Z"))
                .andExpect(jsonPath("$.data.extraction.json").value("{\"data\":{}}"))
                .andExpect(jsonPath("$.data.extraction.totalPages").value(35));
    }

    @Test
    void 저장한_추출_결과가_없으면_data를_비운다() throws Exception {
        mockMvc.perform(get(PATH + "/latest"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":null}", JsonCompareMode.STRICT));
    }

    @Test
    void 빈_파일은_400으로_거절하고_추출하지_않는다() throws Exception {
        mockMvc.perform(multipart(PATH).file(file(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_INVALID"));
        verify(service, never()).extract(anyLong(), any(), any());
    }

    @Test
    void 읽을_수_없는_PDF는_400과_이유를_돌려준다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenThrow(new InvalidAnnouncementPdfException("쪽이 없는 PDF입니다."));

        mockMvc.perform(multipart(PATH).file(file(PDF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_INVALID"))
                .andExpect(jsonPath("$.message").value("쪽이 없는 PDF입니다."));
    }

    @Test
    void 없는_공고는_404를_돌려준다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenThrow(new AnnouncementNotFoundException());

        mockMvc.perform(multipart(PATH).file(file(PDF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_NOT_FOUND"));
    }

    @Test
    void AI_모델이_설정되지_않으면_503을_돌려준다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenThrow(new PdfExtractionUnavailableException());

        mockMvc.perform(multipart(PATH).file(file(PDF)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_EXTRACTION_UNAVAILABLE"));
    }

    @Test
    void AI_모델_호출이_실패하면_502를_돌려주고_내부_원인은_숨긴다() throws Exception {
        when(service.extract(eq(7L), any(), any())).thenThrow(new PdfExtractionFailedException("AI 모델 호출에 실패했습니다.",
                new IllegalStateException("api key=secret")));

        mockMvc.perform(multipart(PATH).file(file(PDF)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_PDF_EXTRACTION_FAILED"))
                .andExpect(jsonPath("$.message").value("AI 모델 호출에 실패했습니다."));
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "announcement.pdf", "application/pdf", content);
    }
}
