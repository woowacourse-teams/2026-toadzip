package com.toadzip.backend.announcement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

/** 공고문 PDF 자동 추출 결과. 같은 PDF를 다시 추출하는 비용을 줄이려고 응답 그대로 남긴다. */
@Getter
@Entity
@Table(name = "announcement_pdf_extractions")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementPdfExtractionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private long announcementId;
    @Column(name = "pdf_sha256", nullable = false, length = 64) private String pdfSha256;
    @Column(nullable = false, length = 500) private String fileName;
    @Column(nullable = false, columnDefinition = "text") private String result;
    @Column(nullable = false) private Instant createdAt;

    public AnnouncementPdfExtractionRecord(long announcementId, String pdfSha256, String fileName, String result) {
        this.announcementId = announcementId;
        this.pdfSha256 = pdfSha256;
        this.fileName = fileName;
        this.result = result;
        createdAt = Instant.now();
    }
}
