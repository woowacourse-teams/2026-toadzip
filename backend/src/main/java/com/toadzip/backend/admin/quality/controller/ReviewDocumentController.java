package com.toadzip.backend.admin.quality.controller;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.ReviewDocument;
import com.toadzip.backend.admin.quality.service.ReviewDocumentService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/quality/{resource}/{id}/documents")
public class ReviewDocumentController {
    private final ReviewDocumentService documents;

    public ReviewDocumentController(ReviewDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public ApiResponse<List<ReviewDocument>> list(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return new ApiResponse<>(documents.list(resource, id, page));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> archive(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestParam MultipartFile file, @RequestParam @NotBlank @Size(max = 2048) String sourceUrl,
            Principal actor) throws IOException {
        if (file.getSize() > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("25MB 이하의 PDF를 선택해 주세요.");
        }
        return new ApiResponse<>(Map.of("sha256", documents.archive(resource, id, file.getOriginalFilename(),
                sourceUrl, file.getBytes(), actor.getName())));
    }

    @GetMapping("/{sha256}")
    public ResponseEntity<byte[]> content(@PathVariable QualityResource resource, @PathVariable long id,
            @PathVariable @Pattern(regexp = "[a-f0-9]{64}") String sha256) {
        var content = documents.content(resource, id, sha256);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @ExceptionHandler(ReviewDocumentService.ReviewDocumentNotFound.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("code", "DOCUMENT_NOT_FOUND", "message", "보관된 원본이 없습니다."));
    }
}
