package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.service.AnnouncementPdfExtractionService;
import com.toadzip.backend.global.response.ApiResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 관리자가 고른 공고문 PDF를 AI 모델로 추출하고, 저장해 둔 이전 결과를 돌려준다. 공고 반영은 PDF 보정 화면에서 검토한 뒤 한다. */
@RestController
@RequestMapping("/api/admin/announcements/{id}/pdf-extractions")
public class AnnouncementPdfExtractionController {

    private final AnnouncementPdfExtractionService service;

    public AnnouncementPdfExtractionController(AnnouncementPdfExtractionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AnnouncementPdfExtraction> extract(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            throw new InvalidAnnouncementPdfException("공고문 PDF 파일을 선택해 주세요.");
        }
        return new ApiResponse<>(service.extract(id, contentOf(file), fileNameOf(file)));
    }

    @GetMapping("/latest")
    public ApiResponse<SavedAnnouncementPdfExtraction> latest(@PathVariable long id) {
        return new ApiResponse<>(service.latest(id));
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
