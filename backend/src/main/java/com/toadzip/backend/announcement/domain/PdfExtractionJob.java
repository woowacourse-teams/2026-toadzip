package com.toadzip.backend.announcement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import static lombok.AccessLevel.PROTECTED;

/**
 * 공고문 PDF 추출 작업(ai_jobs의 한 행). Spring은 작업을 넣고 상태와 결과를 읽기만 한다.
 * 꺼내기·lease·재시도·완료·실패와 PDF 비우기는 Python AI 워커가 쓴다.
 */
@Getter
@Entity
@Table(name = "ai_jobs")
@NoArgsConstructor(access = PROTECTED)
public class PdfExtractionJob {
    public static final String KIND = "ANNOUNCEMENT_PDF_EXTRACTION";
    public static final int MAX_ATTEMPTS = 3;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 50) private String kind;
    @Column(nullable = false) private long announcementId;
    @Column(nullable = false, length = 20) private String requestSource;
    private String requestedBy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PdfExtractionJobStatus status;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private int maxAttempts;
    @Column(nullable = false) private Instant nextAttemptAt;
    private UUID leaseToken;
    private Instant leaseUntil;
    @Column(nullable = false, length = 500) private String fileName;
    @Column(name = "pdf_sha256", nullable = false, length = 64) private String pdfSha256;
    @Column(columnDefinition = "bytea") private byte[] pdf;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String input;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String result;
    @Column(length = 50) private String errorCode;
    @Column(length = 500) private String errorMessage;
    @Column(nullable = false) private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    private PdfExtractionJob(long announcementId, String requestedBy, PdfExtractionSource source, String input,
            Instant now) {
        kind = KIND;
        this.announcementId = announcementId;
        requestSource = "ADMIN";
        this.requestedBy = requestedBy;
        status = PdfExtractionJobStatus.PENDING;
        maxAttempts = MAX_ATTEMPTS;
        nextAttemptAt = now;
        fileName = source.fileName();
        pdfSha256 = source.sha256();
        pdf = source.content();
        this.input = input;
        createdAt = now;
    }

    /** 관리자가 올린 PDF로 바로 처리할 작업을 만든다. input은 워커에 줄 입력 스냅숏 JSON이다. */
    public static PdfExtractionJob requestedByAdmin(long announcementId, String admin, PdfExtractionSource source,
            String input, Instant now) {
        return new PdfExtractionJob(announcementId, admin, source, input, now);
    }
}
