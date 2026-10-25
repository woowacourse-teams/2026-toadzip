package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionModel;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionReply;
import java.io.IOException;
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
    private final AnnouncementPdfExtractionService service =
            new AnnouncementPdfExtractionService(announcements, model, JsonMapper.builder().build());

    @Test
    void 공급표와_임대조건표를_찾지_못한_PDF는_전체_쪽을_보내고_경고한다() throws IOException {
        when(announcements.existsById(7L)).thenReturn(true);
        when(model.extract(any())).thenReturn(new AnnouncementExtractionReply(REPLY,
                new AnnouncementExtractionReply.Usage(900, 300, 50)));

        AnnouncementPdfExtraction result = service.extract(7L, AnnouncementPdfDocumentTest.pdf("cover", "notice"));

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

        assertThatThrownBy(() -> service.extract(7L, new byte[] {1}))
                .isInstanceOf(AnnouncementNotFoundException.class);
        verify(model, never()).extract(any());
    }
}
