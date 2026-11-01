package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.response.PdfExtractionJobResponse;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.service.AnnouncementPdfExtractionService;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자가 고른 공고문 PDF의 AI 추출 작업을 넣고, 작업 상태와 저장된 결과를 돌려준다.
 * 추출은 AI 워커가 따로 하고, 공고 반영은 PDF 보정 화면에서 검토한 뒤 한다.
 */
@RestController
@RequestMapping("/api/admin/announcements/{id}/pdf-extractions")
public class AnnouncementPdfExtractionController {
    private final AnnouncementPdfExtractionService service;

    public AnnouncementPdfExtractionController(AnnouncementPdfExtractionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PdfExtractionJobResponse> request(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        if (file.isEmpty()) {
            throw new InvalidAnnouncementPdfException("공고문 PDF 파일을 선택해 주세요.");
        }
        if (file.getSize() > AnnouncementPdfExtractionService.MAX_PDF_BYTES) {
            throw new InvalidAnnouncementPdfException("공고문 PDF는 30MB 이하만 올릴 수 있습니다.");
        }
        return new ApiResponse<>(service.request(id, authentication.getName(), fileNameOf(file), contentOf(file)));
    }

    /** 공고에 수집된 첨부 PDF로 작업을 넣는다. 화면이 PDF를 다시 올리지 않고 서버가 첨부를 받는다. */
    @PostMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PdfExtractionJobResponse> requestAttachment(
            @PathVariable long id,
            @PathVariable long attachmentId,
            Authentication authentication
    ) {
        return new ApiResponse<>(service.requestAttachment(id, authentication.getName(), attachmentId));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<PdfExtractionJobResponse> job(@PathVariable long id, @PathVariable long jobId) {
        return new ApiResponse<>(service.job(id, jobId));
    }

    @GetMapping("/jobs/active")
    public ApiResponse<PdfExtractionJobResponse> active(@PathVariable long id) {
        return new ApiResponse<>(service.active(id));
    }

    @GetMapping("/latest")
    public ApiResponse<SavedAnnouncementPdfExtraction> latest(@PathVariable long id) {
        return new ApiResponse<>(service.latest(id));
    }

    @ExceptionHandler(AttachmentUnavailableException.class)
    public ResponseEntity<ErrorResponse> attachmentUnavailable(
            AttachmentUnavailableException exception,
            HttpServletRequest request
    ) {
        return AnnouncementAttachmentController.response(exception, request);
    }

    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "공고문.pdf";
        }
        return name.substring(0, Math.min(name.length(), 500));
    }

    private static byte[] contentOf(MultipartFile file) {
        try {
            return file.getBytes();
        }
        catch (IOException exception) {
            throw new InvalidAnnouncementPdfException("올린 PDF 파일을 읽지 못했습니다.", exception);
        }
    }
}
