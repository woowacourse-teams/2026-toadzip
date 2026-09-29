package com.toadzip.backend.announcement.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.dto.response.AttachmentContent;
import com.toadzip.backend.announcement.service.AnnouncementAttachmentService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AnnouncementAttachmentTransferTest {
    @Test
    void 응답_전송을_마치면_파일과_슬롯을_반환한다() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (var file = new TemporaryAttachment(released::incrementAndGet)) {
            file.append(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            var response = new MockHttpServletResponse();
            controller(file).read(1, 2, true, response);
            assertEquals(3, response.getContentAsByteArray().length);
            assertEquals("3", response.getHeader("Content-Length"));
            assertEquals(1, released.get());
            assertThrows(IOException.class, file::prefix);
        }
        assertEquals(1, released.get());
    }

    @Test
    void 클라이언트_연결이_끊겨도_파일과_슬롯을_반환한다() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (var file = new TemporaryAttachment(released::incrementAndGet)) {
            file.append(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            HttpServletResponse response = mock(HttpServletResponse.class);
            ServletOutputStream output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
            org.mockito.Mockito.doThrow(new IOException("client disconnected"))
                    .when(output).write(org.mockito.ArgumentMatchers.any(byte[].class),
                            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
            assertThrows(IOException.class, () -> controller(file).read(1, 2, true, response));
            assertEquals(1, released.get());
            assertThrows(IOException.class, file::prefix);
        }
    }

    private AnnouncementAttachmentController controller(TemporaryAttachment file) {
        var service = mock(AnnouncementAttachmentService.class);
        when(service.read(1, 2, true)).thenReturn(new AttachmentContent("공고.hwp", file));
        return new AnnouncementAttachmentController(service);
    }
}
