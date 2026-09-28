package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.response.AttachmentContent;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.service.AnnouncementAttachmentService;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/announcements/{announcementId}/attachments/{attachmentId}/content")
public class AnnouncementAttachmentController {
    private final AnnouncementAttachmentService service;

    public AnnouncementAttachmentController(AnnouncementAttachmentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<byte[]> read(
            @PathVariable long announcementId,
            @PathVariable long attachmentId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        AttachmentContent content = service.read(announcementId, attachmentId, download);
        ContentDisposition.Builder disposition = ContentDisposition.inline();
        MediaType type = MediaType.APPLICATION_PDF;
        if (download) {
            disposition = ContentDisposition.attachment();
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        String name = content.fileName().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(name, StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(content.bytes().length).body(content.bytes());
    }

    @ExceptionHandler(AttachmentUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(
            AttachmentUnavailableException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNSUPPORTED_SOURCE, NOT_PDF -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            case UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse("ATTACHMENT_" + exception.reason().name(),
                        "첨부파일을 불러오지 못했습니다.", RequestTraceIdResolver.resolve(request)));
    }
}
