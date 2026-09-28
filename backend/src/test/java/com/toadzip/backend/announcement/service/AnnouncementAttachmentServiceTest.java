package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.dto.response.AttachmentSource;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnnouncementAttachmentServiceTest {
    private final AnnouncementAttachmentRepository repository = mock(AnnouncementAttachmentRepository.class);
    private final AnnouncementAttachmentClient client = mock(AnnouncementAttachmentClient.class);
    private final AnnouncementAttachmentService service = new AnnouncementAttachmentService(repository, client);

    @Test
    void 공개_공고에_소속된_파일이_없으면_외부_요청을_하지_않는다() {
        when(repository.findPublicSource(1, 2)).thenReturn(Optional.empty());
        assertEquals(AttachmentUnavailableException.Reason.NOT_FOUND,
                assertThrows(AttachmentUnavailableException.class, () -> service.read(1, 2, false)).reason());
        verifyNoInteractions(client);
    }

    @Test
    void 확장자가_PDF여도_실제_본문이_HTML이면_미리보기를_거절한다() {
        stub("<html>error</html>");
        assertEquals(AttachmentUnavailableException.Reason.NOT_PDF,
                assertThrows(AttachmentUnavailableException.class, () -> service.read(1, 2, false)).reason());
    }

    @Test
    void PDF_본문은_원래_파일명과_함께_미리보기로_반환한다() {
        byte[] bytes = stub("%PDF-1.6 test");
        var content = service.read(1, 2, false);
        assertEquals("공고문.pdf", content.fileName());
        assertArrayEquals(bytes, content.bytes());
    }

    @Test
    void 다운로드에서도_HTML_오류_페이지를_파일로_반환하지_않는다() {
        for (String html : java.util.List.of("<html>error</html>", " \n<!DOCTYPE html><html>error</html>")) {
            stub(html);
            assertEquals(AttachmentUnavailableException.Reason.UPSTREAM_FAILURE,
                    assertThrows(AttachmentUnavailableException.class, () -> service.read(1, 2, true)).reason());
        }
    }

    @Test
    void 다운로드는_PDF가_아닌_원본_바이트도_보존한다() {
        byte[] bytes = stub("PK zip contents");
        assertArrayEquals(bytes, service.read(1, 2, true).bytes());
    }

    private byte[] stub(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        when(repository.findPublicSource(1, 2)).thenReturn(Optional.of(new AttachmentSource("공고문.pdf", "url")));
        when(client.read("url")).thenReturn(bytes);
        return bytes;
    }
}
