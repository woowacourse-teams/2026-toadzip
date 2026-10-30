package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.AnnouncementPdfExtractionRecord;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementPdfExtractionRecordRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionModel;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionReply;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AnnouncementPdfExtractionServiceTest {

    private static final String REPLY = """
            {"data":{"announcement":{"name":"공고"},"complexes":[],"supplyRows":[]},"sharedCounts":[],
            "notApplicable":[],"evidence":[{"path":"announcement.name","page":2,"quote":"공고"}]}
            """;

    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final AnnouncementExtractionModel model = mock(AnnouncementExtractionModel.class);
    private final AnnouncementPdfExtractionRecordRepository records =
            mock(AnnouncementPdfExtractionRecordRepository.class);
    private final AnnouncementPdfExtractionService service = new AnnouncementPdfExtractionService(
            announcements, records, model, JsonMapper.builder().build());

    @Test
    void 공급표와_임대조건표를_찾지_못한_PDF는_전체_쪽을_보내고_경고한다() throws IOException {
        when(announcements.existsById(7L)).thenReturn(true);
        when(model.extract(any())).thenReturn(new AnnouncementExtractionReply(REPLY,
                new AnnouncementExtractionReply.Usage(900, 300, 50)));

        AnnouncementPdfExtraction result = service.extract(7L, AnnouncementPdfDocumentTest.pdf("cover", "notice"), "공고문.pdf");

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(model).extract(sent.capture());
        assertThat(AnnouncementPdfDocument.read(sent.getValue()).pageTexts()).hasSize(2);
        assertThat(result.pages()).containsExactly(1, 2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.warnings()).containsExactly("공급표·임대조건표 쪽을 찾지 못해 전체 2쪽을 보냈습니다.");
        assertThat(result.usage()).isEqualTo(new AnnouncementPdfExtraction.Usage(900, 300, 50));
        assertThat(result.json()).contains("\"page\":2");
    }

    @Test
    void 없는_공고면_PDF를_읽거나_모델을_부르지_않는다() {
        when(announcements.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.extract(7L, new byte[] {1}, "공고문.pdf"))
                .isInstanceOf(AnnouncementNotFoundException.class);
        verify(model, never()).extract(any());
    }

    @Test
    void 추출에_성공하면_PDF_해시와_파일_이름으로_결과를_저장한다() throws Exception {
        byte[] pdf = AnnouncementPdfDocumentTest.pdf("cover");
        when(announcements.existsById(7L)).thenReturn(true);
        when(model.extract(any())).thenReturn(new AnnouncementExtractionReply(REPLY,
                new AnnouncementExtractionReply.Usage(900, 300, null)));

        AnnouncementPdfExtraction result = service.extract(7L, pdf, "공고문.pdf");

        ArgumentCaptor<AnnouncementPdfExtractionRecord> saved =
                ArgumentCaptor.forClass(AnnouncementPdfExtractionRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getAnnouncementId()).isEqualTo(7L);
        assertThat(saved.getValue().getPdfSha256()).isEqualTo(sha256(pdf));
        assertThat(saved.getValue().getFileName()).isEqualTo("공고문.pdf");
        assertThat(JsonMapper.builder().build().readValue(saved.getValue().getResult(),
                AnnouncementPdfExtraction.class)).isEqualTo(result);
    }

    @Test
    void 결과_저장에_실패해도_이미_받은_추출_결과는_돌려준다() throws IOException {
        when(announcements.existsById(7L)).thenReturn(true);
        when(model.extract(any())).thenReturn(new AnnouncementExtractionReply(REPLY,
                new AnnouncementExtractionReply.Usage(900, 300, null)));
        when(records.save(any())).thenThrow(new IllegalStateException("db down"));

        AnnouncementPdfExtraction result = service.extract(7L, AnnouncementPdfDocumentTest.pdf("cover"), "공고문.pdf");

        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void 저장한_최근_추출_결과를_다시_돌려준다() {
        AnnouncementPdfExtraction extraction = new AnnouncementPdfExtraction("{}", List.of(1, 3), 9,
                List.of("경고"), new AnnouncementPdfExtraction.Usage(900, 300, null));
        AnnouncementPdfExtractionRecord record = new AnnouncementPdfExtractionRecord(7L, "ab".repeat(32),
                "공고문.pdf", JsonMapper.builder().build().writeValueAsString(extraction));
        when(announcements.existsById(7L)).thenReturn(true);
        when(records.findFirstByAnnouncementIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(record));

        SavedAnnouncementPdfExtraction saved = service.latest(7L);

        assertThat(saved.fileName()).isEqualTo("공고문.pdf");
        assertThat(saved.pdfSha256()).isEqualTo("ab".repeat(32));
        assertThat(saved.createdAt()).isEqualTo(record.getCreatedAt());
        assertThat(saved.extraction()).isEqualTo(extraction);
        verify(model, never()).extract(any());
    }

    @Test
    void 저장한_추출_결과가_없으면_null을_돌려준다() {
        when(announcements.existsById(7L)).thenReturn(true);
        when(records.findFirstByAnnouncementIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.empty());

        assertThat(service.latest(7L)).isNull();
    }

    @Test
    void 없는_공고의_최근_결과는_404로_막는다() {
        when(announcements.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.latest(7L)).isInstanceOf(AnnouncementNotFoundException.class);
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
